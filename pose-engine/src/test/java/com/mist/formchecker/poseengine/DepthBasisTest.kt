package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 깊이 판정을 무릎 각도에서 엉덩이 높이로 옮긴 것의 회귀 테스트.
 *
 * ## 왜 옮겼는가
 * **"패럴렐"의 정의가 대퇴 수평, 즉 엉덩이가 무릎 높이다.** 무릎 각도는 같은 깊이에서도
 * 대퇴/정강이 비율에 따라 달라지므로, 각도로 패럴렐을 정의하면 사람마다 다른 깊이를 같다고
 * 하거나 같은 깊이를 다르다고 한다.
 *
 * 실측에서 두 사람의 최저점 무릎 굴곡이 109.8°와 125.5°로 16° 차이였는데, 두 사람의
 * 대퇴/정강이 비율 차이(0.6%)는 그 값의 각도 의존 노이즈(7%)보다 작았다 — 즉 16°는
 * 체형으로 설명되지 않고 실제 깊이 차이였다.
 *
 * ## 두 기준은 환산되지 않는다
 * 그래서 어느 쪽을 썼는지 결과에 남긴다. 남기지 않으면 기준선이 생기거나 사라질 때 판정이
 * 조용히 바뀌고, 리포트에서 원인을 찾을 수 없다.
 */
class DepthBasisTest {

    private val thresholds = FormThresholds()

    // ── 비율 기준 경계 ──────────────────────────────────────

    /** 선 자세는 힙이 무릎보다 대퇴 하나만큼 위다. 실측 앱 −0.51~−0.53. */
    @Test
    fun `선 자세 깊이비를 서 있음으로 본다`() {
        assertEquals(DepthLevel.STANDING, thresholds.depthLevelByRatio(-0.52f))
        assertEquals(DepthLevel.STANDING, thresholds.depthLevelByRatio(-0.45f))
    }

    /** 패럴렐 경계는 0이다 — 측정이 아니라 정의다. */
    @Test
    fun `엉덩이가 무릎 높이면 패럴렐이다`() {
        assertEquals(DepthLevel.PARALLEL, thresholds.depthLevelByRatio(0f))
    }

    /**
     * 정확히 0을 경계로 쓰면 측정 오차만큼 부족한 rep을 오류로 보고한다.
     *
     * 실측에서 대퇴가 수평까지 3.08° 남았던 세션의 최저점 깊이비가 −0.026이었다. 관절 중심
     * 추정 오차를 각도로 환산하면 그 정도이므로 허용폭 0.03 안에 들어와야 한다.
     */
    @Test
    fun `측정 오차만큼 부족한 것은 패럴렐로 본다`() {
        assertEquals(DepthLevel.PARALLEL, thresholds.depthLevelByRatio(-0.026f))
        assertEquals(
            "허용폭을 넘으면 부족으로 본다",
            DepthLevel.SHALLOW,
            thresholds.depthLevelByRatio(-0.10f),
        )
    }

    /** 실측 P002의 최저점 +0.060은 확실히 패럴렐 아래였다. */
    @Test
    fun `패럴렐보다 확실히 내려가면 충분으로 본다`() {
        assertEquals(DepthLevel.DEEP, thresholds.depthLevelByRatio(0.060f))
    }

    /** 경계가 순서대로 놓여 있어야 한다. 뒤집히면 어떤 값도 그 단계로 판정되지 않는다. */
    @Test
    fun `경계가 단조롭게 놓여 있다`() {
        val ordered = listOf(-0.60f, -0.40f, -0.03f, 0f, 0.05f, 0.20f)
        val levels = ordered.map { thresholds.depthLevelByRatio(it) }
        assertEquals(
            listOf(
                DepthLevel.STANDING,
                DepthLevel.STANDING,
                DepthLevel.PARALLEL,
                DepthLevel.PARALLEL,
                DepthLevel.DEEP,
                DepthLevel.DEEP,
            ),
            levels,
        )
    }

    // ── 두 기준이 다른 답을 낸다 ────────────────────────────

    /**
     * 같은 깊이에서 두 기준이 갈리는 것을 고정한다. 이 테스트가 깨지면 두 경로가 우연히
     * 같아진 것이고, 그러면 기준을 나눈 이유가 사라진다.
     *
     * 실측 P001의 최저점: 무릎 굴곡 109.8°(내각 70.2°) → 각도 기준으로는 `deepAngle`(100°)
     * 미만이라 DEEP. 깊이비 −0.026 → 높이 기준으로는 패럴렐에 겨우 닿은 PARALLEL.
     * **같은 rep을 두 기준이 다르게 읽는다.**
     */
    @Test
    fun `실측 rep을 두 기준이 다르게 판정한다`() {
        val byAngle = thresholds.depthLevelOf(70.2f)
        val byRatio = thresholds.depthLevelByRatio(-0.026f)

        assertEquals(DepthLevel.DEEP, byAngle)
        assertEquals(DepthLevel.PARALLEL, byRatio)
        assertNotEquals("두 기준은 환산되지 않는다", byAngle, byRatio)
    }

    // ── 분석기가 기준을 고르고 남긴다 ──────────────────────

    private val analyzer = SquatFormAnalyzer()
    private val aspect = 0.75f

    /**
     * 측면 자세. 정강이를 수직으로 두어 굴곡각을 직접 만든다
     * (`CaptureRecorderTest`와 같은 방식).
     */
    private fun sidePose(flexionDegrees: Float): Pose {
        val radians = Math.toRadians(flexionDegrees.toDouble())
        val kneeY = 0.70f
        val femur = 0.20f
        val hipX = (0.50f + femur * kotlin.math.sin(radians)).toFloat()
        val hipY = (kneeY - femur * kotlin.math.cos(radians)).toFloat()

        val points = mutableMapOf<KeypointType, Pair<Float, Float>>()
        fun sides(left: KeypointType, right: KeypointType, x: Float, y: Float) {
            points[left] = (x - 0.03f) to y
            points[right] = (x + 0.03f) to y
        }
        sides(KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP, hipX, hipY)
        sides(KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE, 0.50f, kneeY)
        sides(KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE, 0.50f, 0.90f)
        sides(KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER, hipX, hipY - 0.20f)
        points[KeypointType.NECK] = hipX to (hipY - 0.22f)
        sides(KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL, 0.50f, 0.94f)
        sides(KeypointType.LEFT_BIG_TOE, KeypointType.RIGHT_BIG_TOE, 0.58f, 0.94f)
        sides(KeypointType.LEFT_SMALL_TOE, KeypointType.RIGHT_SMALL_TOE, 0.58f, 0.94f)

        return Pose(
            KeypointType.entries.map { type ->
                val point = points[type]
                if (point == null) Keypoint(type, 0f, 0f, 0f)
                else Keypoint(type, point.first, point.second, 0.9f)
            },
            0L,
        )
    }

    private fun calibration(): StandingCalibration =
        StandingCalibration.from(List(30) { sidePose(0f) to aspect })!!

    private fun featuresAt(flexionDegrees: Float): FrameFeatures = FrameFeatures.from(
        pose = sidePose(flexionDegrees),
        aspectRatio = aspect,
        calibration = calibration(),
        view = CaptureView.SIDE_LEFT,
        activeSide = Side.LEFT,
    )

    @Test
    fun `기준선이 없으면 각도 기준을 쓴다`() {
        val form = analyzer.analyze(sidePose(90f), aspect, CameraAngle.SIDE)

        assertEquals(DepthBasis.KNEE_ANGLE, form.depthBasis)
        assertNull("기준선이 없으면 깊이비도 없다", form.depthRatio)
    }

    @Test
    fun `기준선이 있으면 높이 기준을 쓴다`() {
        val form = analyzer.analyze(
            sidePose(90f), aspect, CameraAngle.SIDE, featuresAt(90f),
        )

        assertEquals(DepthBasis.HIP_HEIGHT, form.depthBasis)
        assertEquals("깊이비를 함께 남긴다", true, form.depthRatio != null)
    }

    /**
     * 무릎 굴곡 90°는 대퇴가 수평이라는 뜻이므로 패럴렐이다. 각도 기준에서는 내각 90°가
     * `deepAngle`(100°) 미만이라 DEEP이 된다 — 같은 자세를 다르게 읽는 실제 사례다.
     */
    @Test
    fun `대퇴 수평은 높이 기준에서 패럴렐이다`() {
        val normalized = analyzer.analyze(
            sidePose(90f), aspect, CameraAngle.SIDE, featuresAt(90f),
        )
        assertEquals(DepthLevel.PARALLEL, normalized.depth)

        val angleBased = analyzer.analyze(sidePose(90f), aspect, CameraAngle.SIDE)
        assertEquals(DepthLevel.DEEP, angleBased.depth)
    }

    @Test
    fun `선 자세는 두 기준 모두 서 있음으로 본다`() {
        val normalized = analyzer.analyze(
            sidePose(0f), aspect, CameraAngle.SIDE, featuresAt(0f),
        )
        val angleBased = analyzer.analyze(sidePose(0f), aspect, CameraAngle.SIDE)

        assertEquals(DepthLevel.STANDING, normalized.depth)
        assertEquals(DepthLevel.STANDING, angleBased.depth)
    }

    /**
     * 정면은 깊이를 지원하지 않는다. 기준선이 있어도 원근 왜곡 때문에 값을 믿을 수 없으므로
     * 판정하지 않는다 — 기준선이 생겼다고 게이트가 열려서는 안 된다.
     */
    @Test
    fun `정면에서는 기준선이 있어도 깊이를 판정하지 않는다`() {
        val form = analyzer.analyze(
            sidePose(90f), aspect, CameraAngle.FRONT, featuresAt(90f),
        )

        assertNull(form.depth)
        assertNull(form.depthBasis)
        assertNull(form.depthRatio)
    }

    /**
     * 상체 숙임은 기준선이 있으면 활성측 힙 하나로 잰다. 측면에서 먼 쪽 힙은 가려져
     * 신뢰도가 낮아(실측 중앙값 0.518) 좌우 중점을 쓰면 값이 통째로 비는 프레임이 생긴다 —
     * 실측 499프레임 중 146프레임이 그랬다.
     */
    @Test
    fun `기준선이 있으면 상체 숙임도 그쪽 값을 쓴다`() {
        val features = featuresAt(60f)
        val form = analyzer.analyze(sidePose(60f), aspect, CameraAngle.SIDE, features)

        assertEquals(features.trunkLean, form.torsoLeanDegrees)
    }

    // ── 골반 좌우 쏠림 ──────────────────────────────────────

    /**
     * 정면 자세. 힙·무릎·발목을 수직으로 쌓고 골반만 [hipOffset]만큼 옆으로 밀어
     * 쏠림을 직접 만든다.
     *
     * `hip_shift_ratio = (hipCenter.x − ankleCenter.x) / 발목 간격`이므로 발목 간격을
     * 0.10(등방 0.075)으로 두면 오프셋을 비율로 바로 환산할 수 있다.
     */
    private fun frontPose(hipOffset: Float): Pose {
        val ankleSpread = 0.10f
        val points = mutableMapOf<KeypointType, Pair<Float, Float>>()
        fun sides(left: KeypointType, right: KeypointType, x: Float, y: Float, spread: Float) {
            points[left] = (x - spread / 2) to y
            points[right] = (x + spread / 2) to y
        }
        sides(KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE, 0.50f, 0.90f, ankleSpread)
        sides(KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE, 0.50f, 0.70f, ankleSpread)
        sides(
            KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
            0.50f + hipOffset, 0.50f, ankleSpread,
        )
        sides(
            KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
            0.50f + hipOffset, 0.30f, 0.20f,
        )
        points[KeypointType.NECK] = (0.50f + hipOffset) to 0.28f
        sides(KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL, 0.50f, 0.94f, ankleSpread)
        sides(KeypointType.LEFT_BIG_TOE, KeypointType.RIGHT_BIG_TOE, 0.50f, 0.98f, ankleSpread)
        sides(KeypointType.LEFT_SMALL_TOE, KeypointType.RIGHT_SMALL_TOE, 0.50f, 0.98f, ankleSpread)

        return Pose(
            KeypointType.entries.map { type ->
                val point = points[type]
                if (point == null) Keypoint(type, 0f, 0f, 0f)
                else Keypoint(type, point.first, point.second, 0.9f)
            },
            0L,
        )
    }

    private fun frontFeatures(hipOffset: Float): FrameFeatures {
        val calibration = StandingCalibration.from(List(30) { frontPose(0f) to aspect })!!
        return FrameFeatures.from(
            pose = frontPose(hipOffset),
            aspectRatio = aspect,
            calibration = calibration,
            view = CaptureView.FRONT,
            activeSide = null,
        )
    }

    private fun frontForm(hipOffset: Float) = analyzer.analyze(
        frontPose(hipOffset), aspect, CameraAngle.FRONT, frontFeatures(hipOffset),
    )

    /**
     * 자체 수집 데이터로 확정한 첫 임계값의 회귀 테스트.
     *
     * 정상 의도 9명 794프레임에서 |쏠림| p99이 0.082였고 한계를 0.10으로 뒀다. 이 값이
     * 전이 가능한 이유는 발목 간격으로 정규화해 **사람 간 변동이 사람 내의 1.04배**까지
     * 내려갔기 때문이다 — 무릎 내측 이동은 어떤 정규화로도 2.4배 아래로 내려가지 않았다.
     */
    @Test
    fun `정상 범위 안의 골반 쏠림은 경고하지 않는다`() {
        val form = frontForm(0f)
        assertEquals(0f, form.hipShiftRatio!!, 0.01f)
        assertEquals(false, form.warnings.contains(FormWarning.HIP_SHIFT))
    }

    @Test
    fun `한계를 넘는 골반 쏠림은 경고한다`() {
        // 오프셋 0.02 → 등방 보정 후 발목 간격(0.075) 대비 0.2 → 한계 0.10의 두 배.
        val form = frontForm(0.02f)
        assertEquals(
            "쏠림이 한계를 넘어야 한다 (실제: ${form.hipShiftRatio})",
            true,
            form.warnings.contains(FormWarning.HIP_SHIFT),
        )
    }

    /** 부호는 어느 쪽으로 쏠렸는지만 알려준다. 판정은 크기로 한다. */
    @Test
    fun `반대쪽으로 쏠려도 같게 판정한다`() {
        assertEquals(
            frontForm(0.02f).warnings.contains(FormWarning.HIP_SHIFT),
            frontForm(-0.02f).warnings.contains(FormWarning.HIP_SHIFT),
        )
    }

    /**
     * 측면에서는 계산하지 않는다. 좌우 간격이 카메라 축 방향이라 원근으로 압축되고,
     * 그 값으로 판정하면 "몸이 얼마나 정확히 측면을 향했는가"를 재게 된다.
     */
    @Test
    fun `측면에서는 골반 쏠림을 판정하지 않는다`() {
        val side = analyzer.analyze(sidePose(90f), aspect, CameraAngle.SIDE, featuresAt(90f))
        assertNull(side.hipShiftRatio)
    }
}
