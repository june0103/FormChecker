package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 측면 — 무릎이 발끝보다 앞으로 나갔는가.
 *
 * ## 무엇을 붙들고 있나
 * 1. **앞쪽을 발 자신이 가리킨다** — `sign(발끝.x − 뒤꿈치.x)`. 촬영 좌/우를 몰라도 되고,
 *    좌우를 뒤집어도 같은 답이 나와야 한다.
 * 2. **분모가 화면에 보이는 발 길이**다. 분자와 같은 두 점 사이 거리라 함께 단축된다.
 * 3. **게이트 셋**(측면 · 발 가시성 · 깊이)이 전부 살아 있어야 한다. 실측에서 발끝을 넘은
 *    rep이 **전부** 발이 짧게 보인 세션에서 나왔다
 *    ([FormThresholds.kneeOverToeLimit] 주석의 표).
 */
class KneePastToeTest {

    private val aspect = 0.75f
    private val thresholds = FormThresholds()

    /**
     * 측면으로 선 사람. 오른쪽(x 증가)이 앞이다.
     *
     * ```
     * 발목   (0.50, 0.90)
     * 무릎   (0.50 + kneeAhead, 0.70)   → 정강이 세로 0.20
     * 뒤꿈치 (0.46, 0.94)
     * 발끝   (0.58, 0.94)               → 등방 발 길이 0.09 (발/정강이 0.45)
     * ```
     *
     * @param kneeAhead 무릎을 앞으로 민 양(정규화 x, 등방보정 전).
     * @param toeX 발끝 x. 줄이면 발이 짧게 보여 게이트가 닫힌다.
     * @param hipY 힙 높이. 0.60이면 정강이 기준 깊이 −0.25로 깊이 게이트를 통과하고,
     *   0.30이면 −1.0이라 아직 서 있는 것으로 본다.
     * @param mirrored 좌우를 뒤집는다. 앞쪽이 x 감소 방향이 된다.
     */
    private fun sidePose(
        kneeAhead: Float = 0f,
        toeX: Float = 0.58f,
        hipY: Float = 0.60f,
        mirrored: Boolean = false,
        footConfidence: Float = 0.9f,
    ): Pose {
        val points = mutableMapOf<KeypointType, Triple<Float, Float, Float>>()
        fun flip(x: Float) = if (mirrored) 1f - x else x
        fun put(type: KeypointType, x: Float, y: Float, score: Float = 0.9f) {
            points[type] = Triple(flip(x), y, score)
        }
        put(KeypointType.LEFT_SHOULDER, 0.48f, hipY - 0.20f)
        put(KeypointType.RIGHT_SHOULDER, 0.52f, hipY - 0.20f)
        put(KeypointType.LEFT_HIP, 0.49f, hipY)
        put(KeypointType.RIGHT_HIP, 0.51f, hipY)
        put(KeypointType.LEFT_KNEE, 0.50f + kneeAhead, 0.70f)
        put(KeypointType.RIGHT_KNEE, 0.50f + kneeAhead, 0.70f)
        put(KeypointType.LEFT_ANKLE, 0.50f, 0.90f)
        put(KeypointType.RIGHT_ANKLE, 0.50f, 0.90f)
        put(KeypointType.NECK, 0.50f, hipY - 0.18f)
        listOf(Side.LEFT, Side.RIGHT).forEach { side ->
            put(Geometry.heel(side), 0.46f, 0.94f, footConfidence)
            put(Geometry.bigToe(side), toeX, 0.94f, footConfidence)
            put(Geometry.smallToe(side), toeX, 0.94f, footConfidence)
        }
        val keypoints = KeypointType.entries.map { type ->
            points[type]?.let { Keypoint(type, it.first, it.second, it.third) }
                ?: Keypoint(type, 0f, 0f, 0f)
        }
        return Pose(keypoints, 0L)
    }

    /**
     * 기준선은 **세션 속성**이다. 발 가시성 게이트가 기준선의 `발/정강이`를 보므로,
     * 게이트를 시험하려면 기준선 자체를 짧은 발로 만들어야 한다 — 프레임 하나의 발끝을
     * 당기는 것으로는 닫히지 않는다(뒤꿈치 들림과 같은 구조).
     */
    private fun calibrationOf(toeX: Float = 0.58f) =
        StandingCalibration.from(List(30) { sidePose(hipY = 0.50f, toeX = toeX) to aspect })

    private fun featuresOf(
        pose: Pose,
        view: CaptureView = CaptureView.SIDE_LEFT,
        calibrationToeX: Float = 0.58f,
    ) = FrameFeatures.from(
        pose = pose,
        aspectRatio = aspect,
        calibration = calibrationOf(calibrationToeX),
        view = view,
        activeSide = Side.LEFT,
    )

    private fun analyze(pose: Pose, calibrationToeX: Float = 0.58f): SquatForm =
        SquatFormAnalyzer(SquatAnalyzerConfig(form = thresholds)).analyze(
            pose = pose,
            frameAspectRatio = aspect,
            cameraAngle = CameraAngle.SIDE,
            features = featuresOf(pose, calibrationToeX = calibrationToeX),
        )

    // ── 특징 계산 ────────────────────────────────────────────

    /** 무릎이 발목 위에 있으면 발끝보다 **뒤**다. 정상 스쿼트의 기본 모양이다. */
    @Test
    fun `무릎이 발목 위면 음수다`() {
        val v = featuresOf(sidePose()).kneeOverToe!!
        assertTrue("발끝보다 뒤여야 한다: $v", v < 0f)
    }

    /**
     * 무릎을 발끝 x까지 밀면 0이다. **기준점이 정의라는 것을 붙든다** —
     * 여기가 흔들리면 임계값의 근거가 통째로 무너진다.
     */
    @Test
    fun `무릎이 발끝 바로 위면 0이다`() {
        val v = featuresOf(sidePose(kneeAhead = 0.08f)).kneeOverToe!!
        assertEquals(0f, v, 1e-3f)
    }

    /** 발 길이만큼 더 밀면 1.0이다 — 분모가 화면에 보이는 발 길이라는 뜻이다. */
    @Test
    fun `발 길이만큼 넘으면 1이다`() {
        val v = featuresOf(sidePose(kneeAhead = 0.08f + 0.12f)).kneeOverToe!!
        assertEquals(1f, v, 1e-2f)
    }

    /**
     * **좌우를 뒤집어도 같은 답이 나와야 한다.** 앞쪽을 발이 가리키므로 촬영 방향을
     * 알 필요가 없다 — 사용자 화면은 `SIDE` 하나로만 판별하므로 이 성질이 필수다.
     */
    @Test
    fun `좌우를 뒤집어도 같은 값이다`() {
        val normal = featuresOf(sidePose(kneeAhead = 0.14f)).kneeOverToe!!
        val flipped = featuresOf(
            sidePose(kneeAhead = 0.14f, mirrored = true),
            view = CaptureView.SIDE_RIGHT,
        ).kneeOverToe!!
        assertEquals(normal, flipped, 1e-3f)
    }

    @Test
    fun `정면에서는 재지 않는다`() {
        val features = FrameFeatures.from(
            pose = sidePose(kneeAhead = 0.20f),
            aspectRatio = aspect,
            calibration = null,
            view = CaptureView.FRONT,
            activeSide = null,
        )
        assertNull("정면에서는 앞뒤가 깊이축이라 잴 수 없다", features.kneeOverToe)
    }

    // ── 게이트 (분석기가 값을 통과시키는가) ─────────────────

    /** 게이트를 통과하면 `SquatForm.kneeOverToe`에 값이 실린다 — rep 판정의 입력이다. */
    @Test
    fun `게이트를 통과하면 값이 실린다`() {
        val form = analyze(sidePose(kneeAhead = 0.14f))
        assertTrue("게이트를 통과해야 한다", form.kneeOverToe != null)
    }

    /**
     * **프레임 단위로는 경고하지 않는다.** 허용폭이 0이라 프레임에 대면 정상 rep의 46%가
     * 걸린다 — 판정은 rep 중앙값으로 한다([FormThresholds.kneePastToeOf]).
     */
    @Test
    fun `프레임 경고로는 나가지 않는다`() {
        val warnings = analyze(sidePose(kneeAhead = 0.30f)).warnings
        assertFalse(warnings.contains(FormWarning.KNEE_PAST_TOE))
    }

    /**
     * **게이트 ①: 발이 짧게 보이면 값을 내지 않는다.**
     *
     * 실측에서 발끝을 넘은 rep이 전부 이 게이트에 걸리는 세션(기준선 발/정강이 0.360)에서
     * 나왔다. 발끝이 뒤꿈치 쪽으로 당겨지면 분자는 커지고 분모는 작아진다.
     */
    @Test
    fun `발이 짧게 보이는 세션은 판정하지 않는다`() {
        val pose = sidePose(kneeAhead = 0.14f, toeX = 0.52f)
        assertTrue("발이 잘 보이는 세션이면 값이 나온다", analyze(pose).kneeOverToe != null)
        assertNull(
            "발이 짧게 보이는 세션은 값 자체를 내지 않는다",
            analyze(pose, calibrationToeX = 0.52f).kneeOverToe,
        )
    }

    /**
     * **게이트 ②: 앉기 전에는 판정하지 않는다.** "무릎이 발끝을 넘지 않는다"는 앉았을 때
     * 성립하는 규칙이다.
     */
    @Test
    fun `서 있을 때는 판정하지 않는다`() {
        assertNull(analyze(sidePose(kneeAhead = 0.20f, hipY = 0.30f)).kneeOverToe)
    }

    // ── rep 단위 판정 ───────────────────────────────────────

    /** 허용폭이 0이다 — 중앙값이 0을 넘으면 경고다. */
    @Test
    fun `중앙값이 0을 넘으면 경고다`() {
        assertEquals(true, thresholds.kneePastToeOf(listOf(0.02f, 0.05f, 0.10f)))
    }

    /** 실측 정상 rep의 중앙값이 −0.05 / −0.14였다. 그 모양은 통과해야 한다. */
    @Test
    fun `정상 rep 모양은 통과한다`() {
        assertEquals(false, thresholds.kneePastToeOf(listOf(-0.14f, -0.11f, -0.05f)))
    }

    /**
     * **이 테스트가 집계를 바꾼 이유다.** 정상 rep에서도 프레임의 6%가 0을 넘는다(실측).
     * 프레임 하나라도 넘으면 경고하는 방식에서는 그것 때문에 46%가 걸렸다. 중앙값은
     * 소수의 튐을 넘긴다.
     */
    @Test
    fun `몇 프레임이 튀어도 중앙값은 넘어가지 않는다`() {
        // 15프레임 중 2개만 0을 넘는다 — 실측 6%와 비슷한 비율.
        val values = List(13) { -0.10f } + listOf(0.05f, 0.07f)
        assertEquals(false, thresholds.kneePastToeOf(values))
    }

    /** 반대로 절반 이상이 넘으면 경고한다 — "그 rep이 그런 모양이었나"를 묻는다. */
    @Test
    fun `절반 이상이 넘으면 경고한다`() {
        val values = List(8) { 0.03f } + List(7) { -0.10f }
        assertEquals(true, thresholds.kneePastToeOf(values))
    }

    /**
     * 판정할 값이 없으면 **null**이다. false가 아니다 — "안 넘었다"와 "못 봤다"는 다르고,
     * 리포트가 못 본 항목을 말해야 한다.
     */
    @Test
    fun `값이 없으면 null이다`() {
        assertNull(thresholds.kneePastToeOf(emptyList()))
    }

    /**
     * **뒤꿈치 들림을 배제하지 않는다.** 깊이-뒤꿈치 관계와 다른 점이다 — 원인도 지시도
     * 같은 방향이라 함께 떠도 모순이 아니다.
     */
    @Test
    fun `측면 판정 목록에 뒤꿈치와 함께 있다`() {
        val checks = RepScorer.checksFor(CameraAngle.SIDE)
        assertTrue(checks.contains(FormWarning.KNEE_PAST_TOE))
        assertTrue(checks.contains(FormWarning.HEEL_RISE))
    }
}
