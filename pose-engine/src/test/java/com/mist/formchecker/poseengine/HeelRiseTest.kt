package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 뒤꿈치 들림.
 *
 * 이전 식은 분자(절대 기준선)와 분모(측면 발 길이)가 둘 다 틀려 실측 3세션 중 1개가
 * 물리적으로 불가능한 값을 냈다(`기술선택_기록.md` 47번). 지금 식은 **같은 프레임의
 * 발끝**을 지면 기준점으로, **정강이 길이**를 분모로 쓴다. 그 두 성질이 깨지면 이 테스트가
 * 깨져야 한다.
 */
class HeelRiseTest {

    private val aspect = 0.75f

    /**
     * 측면으로 선 사람. 왼쪽이 카메라에 가까운 쪽이다.
     *
     * ```
     * 무릎  (0.50, 0.70)
     * 발목  (0.50, 0.90)          → 정강이 0.20
     * 뒤꿈치 (0.46, 0.94 − heelLift)
     * 발끝  (0.58, 0.94)          → 등방 발 길이 ≈ 0.09 (발/정강이 0.45)
     * ```
     *
     * @param heelLift 뒤꿈치를 들어 올린 양(정규화 y). 0이면 바닥에 붙어 있다.
     * @param toeX 발끝 x. 줄이면 발이 짧게 보여 게이트가 닫힌다.
     * @param hipY 힙 높이. 기립은 0.50이고, 0.60이면 `(0.60−0.70)/0.40 = −0.25`로
     *   깊이 부족(SHALLOW) 구간이다 — 0.52로 두면 −0.45라 아직 STANDING이다.
     */
    private fun sidePose(
        heelLift: Float = 0f,
        toeX: Float = 0.58f,
        hipY: Float = 0.50f,
        confidence: Float = 0.9f,
        footConfidence: Float = 0.9f,
    ): Pose {
        val points = mutableMapOf<KeypointType, Triple<Float, Float, Float>>()
        fun put(type: KeypointType, x: Float, y: Float, score: Float = confidence) {
            points[type] = Triple(x, y, score)
        }
        put(KeypointType.LEFT_SHOULDER, 0.48f, hipY - 0.20f)
        put(KeypointType.RIGHT_SHOULDER, 0.52f, hipY - 0.20f)
        put(KeypointType.LEFT_HIP, 0.49f, hipY)
        put(KeypointType.RIGHT_HIP, 0.51f, hipY)
        put(KeypointType.LEFT_KNEE, 0.50f, 0.70f)
        put(KeypointType.RIGHT_KNEE, 0.50f, 0.70f)
        put(KeypointType.LEFT_ANKLE, 0.50f, 0.90f)
        put(KeypointType.RIGHT_ANKLE, 0.50f, 0.90f)
        put(KeypointType.NECK, 0.50f, hipY - 0.18f)
        listOf(Side.LEFT, Side.RIGHT).forEach { side ->
            put(Geometry.heel(side), 0.46f, 0.94f - heelLift, footConfidence)
            put(Geometry.bigToe(side), toeX, 0.94f, footConfidence)
            put(Geometry.smallToe(side), toeX, 0.94f, footConfidence)
        }
        val keypoints = KeypointType.entries.map { type ->
            points[type]?.let { Keypoint(type, it.first, it.second, it.third) }
                ?: Keypoint(type, 0f, 0f, 0f)
        }
        return Pose(keypoints, 0L)
    }

    private fun calibrationOf(pose: Pose) =
        StandingCalibration.from(List(30) { pose to aspect })!!

    private fun featuresOf(
        pose: Pose,
        calibration: StandingCalibration?,
    ) = FrameFeatures.from(
        pose = pose,
        aspectRatio = aspect,
        calibration = calibration,
        view = CaptureView.SIDE_LEFT,
        activeSide = Side.LEFT,
    )

    // ── 특징 계산 ────────────────────────────────────────────

    @Test
    fun `기립 자세에서는 0이다`() {
        val standing = sidePose()
        val features = featuresOf(standing, calibrationOf(standing))
        assertEquals(0f, features.leftHeelRise!!, 1e-4f)
    }

    @Test
    fun `뒤꿈치를 들면 정강이 길이로 정규화된 값이 나온다`() {
        val calibration = calibrationOf(sidePose())
        // 정강이 0.20, 뒤꿈치 0.02 들림 → 0.10
        val features = featuresOf(sidePose(heelLift = 0.02f), calibration)
        assertEquals(0.10f, features.leftHeelRise!!, 1e-3f)
    }

    /**
     * 이전 식이 틀렸던 지점 (1) — 절대 기준선.
     *
     * 사람이 프레임 안에서 위아래로 움직이면 뒤꿈치 y가 그만큼 바뀐다. 발끝과의 차를 쓰면
     * 두 점에서 함께 상쇄돼야 한다.
     */
    @Test
    fun `몸이 화면에서 위아래로 움직여도 값이 바뀌지 않는다`() {
        val calibration = calibrationOf(sidePose())
        val flat = featuresOf(sidePose(), calibration).leftHeelRise!!

        // 발까지 통째로 0.03 위로 옮긴 자세. 뒤꿈치 y만 보면 0.03 들린 것으로 읽힌다.
        val shifted = sidePose()
        val moved = Pose(
            shifted.keypoints.map { kp ->
                if (kp.confidence > 0f) kp.copy(y = kp.y - 0.03f) else kp
            },
            0L,
        )
        val features = featuresOf(moved, calibration)
        assertEquals(flat, features.leftHeelRise!!, 1e-4f)
    }

    /**
     * 이전 식이 틀렸던 지점 (2) — 분모.
     *
     * 발이 짧게 보이는 것은 카메라 높이 탓이고 뒤꿈치가 얼마나 들렸는지와 무관하다.
     * 분모가 발 길이면 같은 들림이 다른 값을 낸다.
     */
    @Test
    fun `발이 짧게 보여도 같은 들림은 같은 값을 낸다`() {
        val longFoot = calibrationOf(sidePose(toeX = 0.58f))
        val shortFoot = calibrationOf(sidePose(toeX = 0.53f))

        val a = featuresOf(sidePose(heelLift = 0.02f, toeX = 0.58f), longFoot).leftHeelRise!!
        val b = featuresOf(sidePose(heelLift = 0.02f, toeX = 0.53f), shortFoot).leftHeelRise!!
        assertEquals(a, b, 1e-4f)
    }

    @Test
    fun `기준선이 없으면 계산하지 않는다`() {
        assertNull(featuresOf(sidePose(heelLift = 0.02f), null).leftHeelRise)
    }

    @Test
    fun `발이 안 보이면 기준선에 0을 채우지 않는다`() {
        // 발 confidence가 하한 미달이면 기립 기준선을 낼 수 없다.
        val calibration = calibrationOf(sidePose(footConfidence = 0.1f))
        assertNull(calibration.standingLeftHeelLift)
        assertNull(featuresOf(sidePose(heelLift = 0.02f), calibration).leftHeelRise)
    }

    @Test
    fun `정면에서는 계산하지 않는다`() {
        val standing = sidePose()
        val calibration = calibrationOf(standing)
        val features = FrameFeatures.from(
            pose = sidePose(heelLift = 0.02f),
            aspectRatio = aspect,
            calibration = calibration,
            view = CaptureView.FRONT,
            activeSide = null,
        )
        assertNull(features.leftHeelRise)
        assertNull(features.rightHeelRise)
    }

    // ── 판정 ─────────────────────────────────────────────────

    private val analyzer = SquatFormAnalyzer()

    private fun analyze(pose: Pose, calibration: StandingCalibration?) = analyzer.analyze(
        pose = pose,
        frameAspectRatio = aspect,
        cameraAngle = CameraAngle.SIDE,
        features = featuresOf(pose, calibration),
    )

    @Test
    fun `허용폭을 넘으면 경고한다`() {
        val calibration = calibrationOf(sidePose())
        // 0.20 정강이에 0.03 들림 → 0.15 > 0.11
        val form = analyze(sidePose(heelLift = 0.03f), calibration)
        assertTrue(FormWarning.HEEL_RISE in form.warnings)
    }

    @Test
    fun `허용폭 안이면 경고하지 않는다`() {
        val calibration = calibrationOf(sidePose())
        // 실측 정상 상한(+0.089)에 해당하는 들림 — 0.20 × 0.089 ≈ 0.0178
        val form = analyze(sidePose(heelLift = 0.0178f), calibration)
        assertFalse(FormWarning.HEEL_RISE in form.warnings)
    }

    /**
     * 게이트. 발이 짧게 보인 세션은 값이 2배 시끄러워 판정하지 않는다 — 실측 P007이
     * `발/정강이` 0.413으로 그 경우였다.
     */
    @Test
    fun `발이 짧게 보이면 판정하지 않는다`() {
        // 발끝을 뒤꿈치 쪽으로 당겨 발 길이를 줄인다 → 발/정강이 하락
        val shortFoot = sidePose(toeX = 0.50f)
        val calibration = calibrationOf(shortFoot)
        assertTrue(
            "게이트가 닫힐 만큼 짧아야 한다",
            calibration.footTibiaRatio < FormThresholds().minHeelFootRatio,
        )
        val form = analyze(sidePose(heelLift = 0.05f, toeX = 0.50f), calibration)
        assertNull(form.heelRise)
        assertFalse(FormWarning.HEEL_RISE in form.warnings)
    }

    @Test
    fun `발이 충분히 보이면 게이트가 열린다`() {
        val calibration = calibrationOf(sidePose())
        assertTrue(calibration.footTibiaRatio >= FormThresholds().minHeelFootRatio)
        assertTrue(analyze(sidePose(heelLift = 0.03f), calibration).heelRise != null)
    }

    @Test
    fun `정면에서는 항목 자체가 꺼진다`() {
        assertFalse(CameraAngle.FRONT.supports(FormCheck.HEEL_LIFT))
        assertTrue(CameraAngle.SIDE.supports(FormCheck.HEEL_LIFT))
        assertFalse(FormWarning.HEEL_RISE in RepScorer.checksFor(CameraAngle.FRONT))
        assertTrue(FormWarning.HEEL_RISE in RepScorer.checksFor(CameraAngle.SIDE))
    }

    // ── 깊이 경고와의 관계 ───────────────────────────────────

    /**
     * 올바른 스쿼트의 깊이는 "뒤꿈치가 뜨지 않는 범위까지"다. 두 경고가 함께 뜨면
     * "뒤꿈치가 떴는데 더 깊게 앉으라"는 서로 반대인 지시가 된다.
     */
    @Test
    fun `뒤꿈치가 뜨면 깊이 부족을 말하지 않는다`() {
        val calibration = calibrationOf(sidePose())
        // 힙을 무릎보다 훨씬 위에 두어 깊이 부족을 만든다.
        val shallowAndLifted = sidePose(heelLift = 0.03f, hipY = 0.60f)
        val form = analyze(shallowAndLifted, calibration)

        assertEquals(DepthLevel.SHALLOW, form.depth)
        assertTrue(FormWarning.HEEL_RISE in form.warnings)
        assertFalse(
            "뒤꿈치가 떴는데 더 깊게 앉으라고 하면 안 된다",
            FormWarning.SHALLOW_DEPTH in form.warnings,
        )
    }

    @Test
    fun `뒤꿈치가 붙어 있으면 깊이 부족은 그대로 말한다`() {
        val calibration = calibrationOf(sidePose())
        val form = analyze(sidePose(hipY = 0.60f), calibration)
        assertEquals(DepthLevel.SHALLOW, form.depth)
        assertTrue(FormWarning.SHALLOW_DEPTH in form.warnings)
        assertFalse(FormWarning.HEEL_RISE in form.warnings)
    }

    /**
     * 게이트가 닫힌 세션에서 깊이까지 잃으면 두 항목을 동시에 잃는다. 뒤꿈치를 판정하지
     * 못했을 때는 깊이 경고를 억누르지 않아야 한다.
     */
    @Test
    fun `뒤꿈치를 판정하지 못하면 깊이 경고는 살아 있다`() {
        val calibration = calibrationOf(sidePose(toeX = 0.50f))
        val form = analyze(sidePose(heelLift = 0.05f, toeX = 0.50f, hipY = 0.60f), calibration)
        assertNull(form.heelRise)
        assertTrue(FormWarning.SHALLOW_DEPTH in form.warnings)
    }

    /**
     * 활성측을 못 정한 측면 세션에서는 판정하지 않는다. 좌우 최댓값으로 대체하면 가려진
     * 먼 쪽 추정이 경고를 낸다.
     */
    @Test
    fun `활성측이 없으면 판정하지 않는다`() {
        val calibration = calibrationOf(sidePose())
        val features = FrameFeatures.from(
            pose = sidePose(heelLift = 0.05f),
            aspectRatio = aspect,
            calibration = calibration,
            view = CaptureView.SIDE_LEFT,
            activeSide = null,
        )
        assertNull(features.heelRise)
        // 좌우 값은 남아 있다 — 수집용이다.
        assertTrue(features.leftHeelRise != null)

        val form = analyzer.analyze(
            pose = sidePose(heelLift = 0.05f),
            frameAspectRatio = aspect,
            cameraAngle = CameraAngle.SIDE,
            features = features,
        )
        assertNull(form.heelRise)
        assertFalse(FormWarning.HEEL_RISE in form.warnings)
    }

    @Test
    fun `뒤꿈치 경고가 깊이 부족보다 먼저 온다`() {
        assertTrue(FormWarning.HEEL_RISE.ordinal < FormWarning.SHALLOW_DEPTH.ordinal)
    }
}
