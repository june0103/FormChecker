package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ── 무릎–발끝 정렬 ──────────────────────────────────────

    /**
     * 무릎만 [kneeOffset]만큼 옮긴 정면 자세. 발끝은 발목 위에 둔다.
     *
     * 발목 간격을 0.10(등방 0.075)으로 두었으므로 오프셋을 그 값으로 나누면 비율이 된다.
     */
    /**
     * 정면 자세. 발목·발끝은 고정하고 무릎만 [kneeOffset]만큼 옮겨 정렬 오차를 만든다.
     *
     * [hipY]로 굴곡을 조절한다 — 힙이 무릎 높이에 가까우면 굴곡이 커진다. 판정에 굴곡
     * 게이트(60°)가 있으므로 선 자세와 앉은 자세를 구분해 만들어야 한다.
     *
     * 발목 간격을 0.10으로 두었으므로 오프셋을 등방 보정한 값(0.075)으로 나누면 비율이
     * 된다.
     */
    private fun kneeShiftedPose(kneeOffset: Float, hipY: Float = SQUAT_HIP_Y): Pose {
        val spread = 0.10f
        val points = mutableMapOf<KeypointType, Pair<Float, Float>>()
        // 사람의 왼쪽은 화면 오른쪽(x 큼)에 나타난다 — 엔진의 부호 규약과 맞춰야 한다.
        // 여기를 뒤집으면 내측/외측 판정이 통째로 반대가 된다.
        fun sides(left: KeypointType, right: KeypointType, x: Float, y: Float, s: Float) {
            points[left] = (x + s / 2) to y
            points[right] = (x - s / 2) to y
        }
        sides(KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE, 0.50f, 0.90f, spread)
        // 내측(+)은 왼쪽 무릎을 x 감소, 오른쪽 무릎을 x 증가 방향으로 옮기는 것이다.
        points[KeypointType.LEFT_KNEE] = (0.50f + spread / 2 - kneeOffset) to 0.70f
        points[KeypointType.RIGHT_KNEE] = (0.50f - spread / 2 + kneeOffset) to 0.70f
        // 힙은 좁게 모아 둔다 — 무릎보다 안쪽에 있어야 굴곡각이 만들어진다.
        sides(KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP, 0.50f, hipY, 0.04f)
        sides(
            KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
            0.50f, hipY - 0.20f, 0.20f,
        )
        points[KeypointType.NECK] = 0.50f to (hipY - 0.22f)
        sides(KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL, 0.50f, 0.94f, spread)
        sides(KeypointType.LEFT_BIG_TOE, KeypointType.RIGHT_BIG_TOE, 0.50f, 0.98f, spread)
        sides(KeypointType.LEFT_SMALL_TOE, KeypointType.RIGHT_SMALL_TOE, 0.50f, 0.98f, spread)

        return Pose(
            KeypointType.entries.map { type ->
                val point = points[type]
                if (point == null) Keypoint(type, 0f, 0f, 0f)
                else Keypoint(type, point.first, point.second, 0.9f)
            },
            0L,
        )
    }

    private fun kneeForm(kneeOffset: Float, hipY: Float = SQUAT_HIP_Y): SquatForm {
        val calibration = StandingCalibration.from(
            List(30) { kneeShiftedPose(0f, STANDING_HIP_Y) to aspect },
        )!!
        val features = FrameFeatures.from(
            pose = kneeShiftedPose(kneeOffset, hipY),
            aspectRatio = aspect,
            calibration = calibration,
            view = CaptureView.FRONT,
            activeSide = null,
        )
        return analyzer.analyze(
            kneeShiftedPose(kneeOffset, hipY), aspect, CameraAngle.FRONT, features,
        )
    }

    /**
     * 기준점 0은 정의다 — 올바른 스쿼트는 무릎이 발끝 방향으로 내려간다. 실측 11명
     * 253 rep 최저점의 중앙값이 0.0000으로 그 정의가 확인됐다.
     */
    @Test
    fun `무릎이 발끝 위에 있으면 정상이다`() {
        val form = kneeForm(0f)
        assertEquals(0f, form.kneeToeDeviation!!, 0.01f)
        assertEquals(KneeAlignment.GOOD, form.kneeAlignment)
        assertTrue(form.warnings.none { it == FormWarning.KNEE_VALGUS })
        assertTrue(form.warnings.none { it == FormWarning.KNEE_FLARED })
    }

    /** 안으로 말리면 valgus. 부상 위험이 큰 쪽이다. */
    @Test
    fun `무릎이 안으로 말리면 valgus로 본다`() {
        val form = kneeForm(0.02f)
        assertEquals(KneeAlignment.VALGUS, form.kneeAlignment)
        assertTrue(form.warnings.contains(FormWarning.KNEE_VALGUS))
    }

    /**
     * **과도하게 벌리는 것도 오류다.** 두 상태(GOOD/VALGUS)로 두면 "많이 벌리면 벌릴수록
     * 좋다"가 되어 이 오류를 아예 보지 못한다.
     */
    @Test
    fun `무릎이 과도하게 벌어지면 오류로 본다`() {
        val form = kneeForm(-0.02f)
        assertEquals(KneeAlignment.FLARED, form.kneeAlignment)
        assertTrue(form.warnings.contains(FormWarning.KNEE_FLARED))
    }

    /** 허용폭 안이면 양쪽 다 정상이다. 사람내 MAD 0.0333의 3배에서 나온 값이다. */
    @Test
    fun `허용폭 안의 벗어남은 정상이다`() {
        // 오프셋 0.007 → 등방 발목 간격 0.075 대비 약 0.09 < 허용폭 0.10
        assertEquals(KneeAlignment.GOOD, kneeForm(0.007f).kneeAlignment)
        assertEquals(KneeAlignment.GOOD, kneeForm(-0.007f).kneeAlignment)
    }

    /**
     * 측면에서는 판정하지 않는다. 좌우 간격이 카메라 축 방향이라 무릎·발끝이 겹쳐
     * 값이 무의미해진다.
     */
    @Test
    fun `측면에서는 무릎 정렬을 판정하지 않는다`() {
        val side = analyzer.analyze(sidePose(90f), aspect, CameraAngle.SIDE, featuresAt(90f))
        assertNull(side.kneeToeDeviation)
        assertNull(side.kneeAlignment)
    }

    /**
     * 무릎폭 증가 배수 방식은 실측에서 무작동이었다 — 정상 표본의 배수가 1.53~2.04인데
     * 임계값이 1.1이라 통과율 100%다. 그 사실을 테스트로 고정해, 되살릴 때 이 숫자를
     * 다시 조사하지 않게 한다.
     */
    @Test
    fun `무릎폭 배수 방식은 정상 표본을 전부 통과시킨다`() {
        // 실측 사람별 배수 최소 1.58 ~ 최대 2.04.
        listOf(1.53f, 1.58f, 1.79f, 2.04f).forEach { gain ->
            assertEquals(
                "배수 $gain 은 임계값 ${thresholds.valgusSpreadGain} 위라 통과한다",
                KneeAlignment.GOOD,
                thresholds.kneeAlignmentOf(standingRatio = 0.8f, bottomRatio = 0.8f * gain),
            )
        }
    }

    // ── 기준선이 없어도 되는 특징 ───────────────────────────

    /**
     * 골반 쏠림과 무릎–발끝 정렬은 **발목 간격**으로 정규화하므로 선 자세 기준선이 필요
     * 없다. 기준선을 요구하면 캘리브레이션을 안 한 사용자에게서 이 두 판정을 이유 없이
     * 빼앗는다 — 처음 구현이 실제로 그랬다.
     */
    @Test
    fun `기준선이 없어도 골반 쏠림과 무릎 정렬을 낸다`() {
        val features = FrameFeatures.from(
            pose = kneeShiftedPose(0.02f),
            aspectRatio = aspect,
            calibration = null,
            view = CaptureView.FRONT,
            activeSide = null,
        )

        assertNotNull("골반 쏠림", features.hipShiftRatio)
        assertNotNull("무릎–발끝(왼)", features.leftKneeToeDeviation)
        assertNotNull("무릎–발끝(오)", features.rightKneeToeDeviation)

        val form = analyzer.analyze(
            kneeShiftedPose(0.02f), aspect, CameraAngle.FRONT, features,
        )
        assertEquals(KneeAlignment.VALGUS, form.kneeAlignment)
    }

    /** 거리 특징은 분모가 없으므로 기준선 없이는 낼 수 없다. 추정값을 만들지 않는다. */
    @Test
    fun `기준선이 없으면 거리 특징은 비운다`() {
        val features = FrameFeatures.from(
            pose = sidePose(90f),
            aspectRatio = aspect,
            calibration = null,
            view = CaptureView.SIDE_LEFT,
            activeSide = Side.LEFT,
        )

        assertNull("깊이 비율", features.depthRatio)
        assertNull("엉덩이 하강", features.hipDropRatio)
        assertNull("뒤꿈치 들림", features.leftHeelRise)
        assertNull("무릎 내측 이동", features.leftMedialKneeDisplacement)
        // 각도는 기준선과 무관하므로 남아야 한다.
        assertNotNull("무릎 굴곡", features.representativeKneeFlexion)
    }

    /**
     * **선 자세에서는 판정하지 않는다.**
     *
     * 무릎이 발끝보다 뒤(깊이 방향)에 있고 발끝은 바깥으로 벌어져 있어, 2D 정면 투영에서
     * 무릎이 항상 "안쪽"으로 읽힌다. 실측 선 자세 편차가 +0.309(중앙값)로 허용폭 초과율이
     * 100%였다 — 게이트가 없으면 가만히 서 있어도 "무릎을 벌려주세요"가 뜬다.
     */
    @Test
    fun `선 자세에서는 무릎 정렬을 판정하지 않는다`() {
        val standing = kneeShiftedPose(kneeOffset = 0f, hipY = STANDING_HIP_Y)
        val features = FrameFeatures.from(
            pose = standing,
            aspectRatio = aspect,
            calibration = null,
            view = CaptureView.FRONT,
            activeSide = null,
        )
        // 특징 자체는 계산된다 — 판정만 보류한다.
        assertNotNull("특징은 계산된다", features.leftKneeToeDeviation)

        val form = analyzer.analyze(standing, aspect, CameraAngle.FRONT, features)
        assertNull("선 자세에서는 판정하지 않는다", form.kneeAlignment)
        assertTrue(
            "선 자세에서 무릎 경고가 뜨면 안 된다",
            form.warnings.none {
                it == FormWarning.KNEE_VALGUS || it == FormWarning.KNEE_FLARED
            },
        )
    }

    /** 굴곡이 게이트를 넘으면 다시 판정한다. 게이트가 판정을 영구히 막지 않는다. */
    @Test
    fun `굴곡이 충분하면 판정을 재개한다`() {
        assertNull(kneeForm(0.02f, hipY = STANDING_HIP_Y).kneeAlignment)
        assertEquals(KneeAlignment.VALGUS, kneeForm(0.02f).kneeAlignment)
    }

    /**
     * 자세 생성기가 실제로 게이트를 넘는 깊이를 만드는지 고정한다.
     *
     * 게이트는 무릎 각도가 아니라 **깊이**다 — 정면 투영에서 무릎 각도는 무릎이 바깥으로
     * 이동해야 커지므로, 무릎을 모으는 오류가 게이트 신호를 억제한다(실측 상관 −0.586).
     */
    @Test
    fun `자세 생성기가 깊이 게이트를 넘긴다`() {
        fun depthOf(hipY: Float) = FrameFeatures.from(
            pose = kneeShiftedPose(0f, hipY),
            aspectRatio = aspect,
            calibration = null,
            view = CaptureView.FRONT,
            activeSide = null,
        ).shinDepthRatio!!

        val squat = depthOf(SQUAT_HIP_Y)
        val standing = depthOf(STANDING_HIP_Y)
        assertTrue(
            "앉은 자세 깊이 $squat 는 게이트 ${thresholds.kneeToeMinDepth} 이상",
            squat >= thresholds.kneeToeMinDepth,
        )
        assertTrue(
            "선 자세 깊이 $standing 는 게이트 미만",
            standing < thresholds.kneeToeMinDepth,
        )
    }

    /**
     * **무릎을 모으면 정면 투영 굴곡이 작아진다** — 그래서 굴곡으로 게이트하면 잡으려는
     * 오류가 게이트를 닫는다. 깊이 게이트는 그 영향을 받지 않아야 한다.
     */
    @Test
    fun `무릎을 모아도 깊이 게이트는 열린다`() {
        fun features(offset: Float) = FrameFeatures.from(
            pose = kneeShiftedPose(offset),
            aspectRatio = aspect,
            calibration = null,
            view = CaptureView.FRONT,
            activeSide = null,
        )

        val aligned = features(0f)
        val kneesIn = features(0.02f)
        // 굴곡은 줄어들 수 있지만 깊이는 유지된다.
        assertTrue(
            "무릎을 모아도 깊이 게이트를 넘는다 (실제: ${kneesIn.shinDepthRatio})",
            kneesIn.shinDepthRatio!! >= thresholds.kneeToeMinDepth,
        )
        assertEquals(
            "깊이는 무릎의 좌우 이동에 거의 영향받지 않는다",
            aligned.shinDepthRatio!!,
            kneesIn.shinDepthRatio!!,
            0.05f,
        )
        // 그리고 판정이 실제로 난다.
        assertEquals(
            KneeAlignment.VALGUS,
            analyzer.analyze(kneeShiftedPose(0.02f), aspect, CameraAngle.FRONT, kneesIn)
                .kneeAlignment,
        )
    }

    private companion object {
        /** 앉은 자세 힙 높이. 무릎(0.70)에 가까워 굴곡이 크다. */
        const val SQUAT_HIP_Y = 0.72f

        /** 선 자세 힙 높이. 다리가 곧게 펴져 굴곡이 게이트 아래다. */
        const val STANDING_HIP_Y = 0.50f
    }
}
