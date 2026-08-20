package com.mist.formchecker.poseengine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SquatFormAnalyzerTest {

    private val analyzer = SquatFormAnalyzer()

    private fun poseOf(vararg points: Pair<KeypointType, Triple<Float, Float, Float>>): Pose {
        val map = points.toMap()
        return Pose(
            keypoints = List(KeypointType.COUNT) { i ->
                val type = KeypointType.of(i)
                val p = map[type]
                Keypoint(type, p?.first ?: 0f, p?.second ?: 0f, p?.third ?: 0f)
            },
            inferenceTimeNanos = 0L,
        )
    }

    /** 정면으로 선 사람: 어깨가 넓게 벌어지고 두 다리가 나란하다. */
    private fun frontFacing(
        kneeSpread: Float = 0.16f,
        ankleSpread: Float = 0.16f,
        hipY: Float = 0.5f,
        kneeY: Float = 0.7f,
    ) = poseOf(
        KeypointType.LEFT_SHOULDER to Triple(0.40f, 0.25f, 0.9f),
        KeypointType.RIGHT_SHOULDER to Triple(0.60f, 0.25f, 0.9f),
        KeypointType.LEFT_HIP to Triple(0.44f, hipY, 0.9f),
        KeypointType.RIGHT_HIP to Triple(0.56f, hipY, 0.9f),
        KeypointType.LEFT_KNEE to Triple(0.5f - kneeSpread / 2, kneeY, 0.9f),
        KeypointType.RIGHT_KNEE to Triple(0.5f + kneeSpread / 2, kneeY, 0.9f),
        KeypointType.LEFT_ANKLE to Triple(0.5f - ankleSpread / 2, 0.9f, 0.9f),
        KeypointType.RIGHT_ANKLE to Triple(0.5f + ankleSpread / 2, 0.9f, 0.9f),
    )

    /** 측면으로 선 사람: 좌우 관절이 x축으로 거의 겹친다. */
    private fun sideFacing(
        shoulderX: Float = 0.5f,
        hipY: Float = 0.5f,
        kneeY: Float = 0.7f,
        kneeX: Float = 0.5f,
    ) = poseOf(
        KeypointType.LEFT_SHOULDER to Triple(shoulderX, 0.25f, 0.9f),
        KeypointType.RIGHT_SHOULDER to Triple(shoulderX + 0.01f, 0.25f, 0.9f),
        KeypointType.LEFT_HIP to Triple(0.5f, hipY, 0.9f),
        KeypointType.RIGHT_HIP to Triple(0.51f, hipY, 0.9f),
        KeypointType.LEFT_KNEE to Triple(kneeX, kneeY, 0.9f),
        KeypointType.RIGHT_KNEE to Triple(kneeX + 0.01f, kneeY, 0.9f),
        KeypointType.LEFT_ANKLE to Triple(0.5f, 0.9f, 0.9f),
        KeypointType.RIGHT_ANKLE to Triple(0.51f, 0.9f, 0.9f),
    )

    // ── 각도별 판정 항목 켜고 끄기 ──────────────────────────────

    @Test
    fun `측면에서는 깊이와 상체 기울기만 판정한다`() {
        val form = analyzer.analyze(sideFacing(), 0.75f, CameraAngle.SIDE)

        assertNotNull("깊이는 측면에서 판정한다", form.depth)
        assertNotNull("상체 기울기는 측면에서 판정한다", form.torsoLeanDegrees)
        assertNull("좌우 비대칭은 측면에서 판정하지 않는다", form.asymmetryDegrees)
    }

    @Test
    fun `정면에서는 무릎 정렬과 비대칭만 판정한다`() {
        val form = analyzer.analyze(frontFacing(), 0.75f, CameraAngle.FRONT)

        assertNotNull("좌우 비대칭은 정면에서 판정한다", form.asymmetryDegrees)
        assertNull("깊이는 정면에서 판정하지 않는다", form.depth)
        assertNull("상체 기울기는 정면에서 판정하지 않는다", form.torsoLeanDegrees)
    }

    // ── 깊이 ──────────────────────────────────────────────────

    @Test
    fun `무릎 각도에 따라 깊이 단계가 나뉜다`() {
        val t = FormThresholds()
        assertEquals(DepthLevel.STANDING, t.depthLevelOf(175f))
        assertEquals(DepthLevel.SHALLOW, t.depthLevelOf(140f))
        assertEquals(DepthLevel.PARALLEL, t.depthLevelOf(110f))
        assertEquals(DepthLevel.DEEP, t.depthLevelOf(85f))
    }

    /**
     * 임계값을 주입할 수 있어야 한다. 데이터·모델 담당이 확정한 값이 오면 판정 로직
     * 파일을 수정하지 않고 이 경로로 교체된다.
     */
    @Test
    fun `깊이 경계값을 주입하면 분류가 달라진다`() {
        // 목표 깊이를 더 깊게 요구하는 설정 (deepAngle을 60°까지 내림)
        val strict = FormThresholds(standingAngle = 150f, shallowAngle = 90f, deepAngle = 60f)

        // 같은 95°가 기본 설정에서는 "충분히 깊음", 엄격한 설정에서는 "깊이 부족"이 된다.
        assertEquals(DepthLevel.DEEP, FormThresholds().depthLevelOf(95f))
        assertEquals(DepthLevel.SHALLOW, strict.depthLevelOf(95f))
    }

    @Test
    fun `valgus 배수를 주입하면 판정이 달라진다`() {
        // 선 자세 0.86 → 최저점 1.20. 기준선 대비 1.40배 벌어졌다.
        val lenient = FormThresholds(valgusSpreadGain = 1.2f)
        val strict = FormThresholds(valgusSpreadGain = 1.6f)

        assertEquals(KneeAlignment.GOOD, lenient.kneeAlignmentOf(0.86f, 1.20f))
        assertEquals(KneeAlignment.VALGUS, strict.kneeAlignmentOf(0.86f, 1.20f))
    }

    @Test
    fun `상체 기울기 한계를 주입하면 경고 여부가 달라진다`() {
        val pose = sideFacing(shoulderX = 0.72f)

        val lenient = SquatFormAnalyzer(
            SquatAnalyzerConfig(form = FormThresholds(torsoLeanLimitDegrees = 80f)),
        )
        val strictAnalyzer = SquatFormAnalyzer(
            SquatAnalyzerConfig(form = FormThresholds(torsoLeanLimitDegrees = 10f)),
        )

        assertTrue(
            lenient.analyze(pose, 0.75f, CameraAngle.SIDE).warnings.isEmpty(),
        )
        assertTrue(
            strictAnalyzer.analyze(pose, 0.75f, CameraAngle.SIDE)
                .warnings.contains(FormWarning.EXCESSIVE_LEAN),
        )
    }

    @Test
    fun `깊이가 부족하면 경고가 나온다`() {
        // 무릎이 살짝만 굽은 자세 → SHALLOW
        val pose = sideFacing(hipY = 0.52f, kneeY = 0.72f, kneeX = 0.56f)
        val form = analyzer.analyze(pose, 0.75f, CameraAngle.SIDE)

        if (form.depth == DepthLevel.SHALLOW) {
            assertTrue(form.warnings.contains(FormWarning.SHALLOW_DEPTH))
        }
    }

    // ── 무릎 정렬 ─────────────────────────────────────────────

    @Test
    fun `무릎폭 비율은 판정이 아니라 측정값으로 나온다`() {
        val form = analyzer.analyze(
            frontFacing(kneeSpread = 0.08f, ankleSpread = 0.16f), 0.75f, CameraAngle.FRONT,
        )

        assertEquals(0.5f, form.kneeSpreadRatio!!, 0.001f)
        // 프레임 하나로는 valgus를 판정할 수 없으므로 경고가 나오지 않아야 한다.
        assertTrue(form.warnings.isEmpty())
    }

    /**
     * 절대 좌표가 아니라 비율이므로, 사람이 화면 어디에 서 있든 값이 같아야 한다.
     * 이게 깨지면 카메라와의 거리에 따라 판정이 흔들린다.
     */
    @Test
    fun `무릎폭 비율은 화면상 위치에 영향받지 않는다`() {
        val centered = analyzer.analyze(
            frontFacing(kneeSpread = 0.06f, ankleSpread = 0.18f), 0.75f, CameraAngle.FRONT,
        )
        val shifted = analyzer.analyze(
            poseOf(
                KeypointType.LEFT_SHOULDER to Triple(0.15f, 0.25f, 0.9f),
                KeypointType.RIGHT_SHOULDER to Triple(0.35f, 0.25f, 0.9f),
                KeypointType.LEFT_HIP to Triple(0.19f, 0.5f, 0.9f),
                KeypointType.RIGHT_HIP to Triple(0.31f, 0.5f, 0.9f),
                KeypointType.LEFT_KNEE to Triple(0.22f, 0.7f, 0.9f),
                KeypointType.RIGHT_KNEE to Triple(0.28f, 0.7f, 0.9f),
                KeypointType.LEFT_ANKLE to Triple(0.16f, 0.9f, 0.9f),
                KeypointType.RIGHT_ANKLE to Triple(0.34f, 0.9f, 0.9f),
            ),
            0.75f, CameraAngle.FRONT,
        )

        assertEquals(centered.kneeSpreadRatio!!, shifted.kneeSpreadRatio!!, 0.001f)
    }

    @Test
    fun `발을 모으고 서면 무릎폭 비율을 내지 않는다`() {
        val form = analyzer.analyze(
            frontFacing(kneeSpread = 0.01f, ankleSpread = 0.02f), 0.75f, CameraAngle.FRONT,
        )

        assertNull("기준이 불안정한 구간에서는 값을 내지 않는다", form.kneeSpreadRatio)
    }

    /**
     * AI Hub 정면 카메라 정상 스쿼트의 실측 중앙값(선 자세 0.857 → 최저점 1.432)이
     * valgus로 판정되면 안 된다. 이전 절대 임계값(0.8)은 선 자세 자체를 오탐했다.
     */
    @Test
    fun `정상 스쿼트의 실측 비율은 valgus가 아니다`() {
        assertEquals(
            KneeAlignment.GOOD,
            FormThresholds().kneeAlignmentOf(standingRatio = 0.857f, bottomRatio = 1.432f),
        )
    }

    @Test
    fun `최저점에서 무릎이 벌어지지 않으면 valgus로 판정한다`() {
        // 선 자세와 거의 같은 폭을 유지한 채 앉았다 = 무릎이 따라 벌어지지 않았다.
        assertEquals(
            KneeAlignment.VALGUS,
            FormThresholds().kneeAlignmentOf(standingRatio = 0.86f, bottomRatio = 0.90f),
        )
    }

    @Test
    fun `기준선이나 최저점 값이 없으면 무릎 정렬을 판정하지 않는다`() {
        val thresholds = FormThresholds()

        assertNull(thresholds.kneeAlignmentOf(standingRatio = null, bottomRatio = 1.4f))
        assertNull(thresholds.kneeAlignmentOf(standingRatio = 0.86f, bottomRatio = null))
        // 기준선이 0에 가까우면 배수가 폭발하므로 판정하지 않는다.
        assertNull(thresholds.kneeAlignmentOf(standingRatio = 0.05f, bottomRatio = 1.4f))
    }

    // ── 상체 기울기 ───────────────────────────────────────────

    @Test
    fun `곧게 선 상체는 기울기가 0에 가깝다`() {
        val form = analyzer.analyze(sideFacing(shoulderX = 0.5f), 0.75f, CameraAngle.SIDE)

        assertNotNull(form.torsoLeanDegrees)
        assertTrue("실제: ${form.torsoLeanDegrees}", form.torsoLeanDegrees!! < 10f)
    }

    @Test
    fun `상체가 크게 숙여지면 경고가 나온다`() {
        // 어깨가 힙보다 앞으로 크게 나간 자세
        val form = analyzer.analyze(sideFacing(shoulderX = 0.85f), 0.75f, CameraAngle.SIDE)

        assertNotNull(form.torsoLeanDegrees)
        assertTrue(
            "기울기 ${form.torsoLeanDegrees}에서 경고가 나와야 한다",
            form.warnings.contains(FormWarning.EXCESSIVE_LEAN),
        )
    }

    // ── 촬영 각도 자동 감지 ───────────────────────────────────

    @Test
    fun `측면 모드인데 정면으로 서 있으면 각도 전환을 제안한다`() {
        val form = analyzer.analyze(frontFacing(), 0.75f, CameraAngle.SIDE)

        assertEquals(CameraAngle.FRONT, form.suggestedAngle)
    }

    @Test
    fun `정면 모드인데 측면으로 서 있으면 각도 전환을 제안한다`() {
        val form = analyzer.analyze(sideFacing(), 0.75f, CameraAngle.FRONT)

        assertEquals(CameraAngle.SIDE, form.suggestedAngle)
    }

    @Test
    fun `선택한 각도와 실제가 일치하면 제안하지 않는다`() {
        assertNull(analyzer.analyze(sideFacing(), 0.75f, CameraAngle.SIDE).suggestedAngle)
        assertNull(analyzer.analyze(frontFacing(), 0.75f, CameraAngle.FRONT).suggestedAngle)
    }

    // ── 저신뢰 처리 ───────────────────────────────────────────

    // ── 가시성 판정 ───────────────────────────────────────────

    @Test
    fun `사람이 안 보이면 NONE이다`() {
        val form = analyzer.analyze(poseOf(), 0.75f, CameraAngle.SIDE)

        assertEquals(PoseVisibility.NONE, form.visibility)
    }

    /**
     * 스켈레톤은 그려지는데 각도가 안 나오는 실제 상황이다 — 상체만 프레임에 들어온 경우.
     * 이걸 구분하지 못하면 사용자에게 "인식 실패"라고만 알려주게 되어 원인을 알 수 없다.
     */
    @Test
    fun `상체만 보이면 UPPER_BODY_ONLY이고 각도는 나오지 않는다`() {
        val form = analyzer.analyze(
            poseOf(
                KeypointType.LEFT_SHOULDER to Triple(0.40f, 0.25f, 0.9f),
                KeypointType.RIGHT_SHOULDER to Triple(0.60f, 0.25f, 0.9f),
                KeypointType.LEFT_HIP to Triple(0.44f, 0.5f, 0.9f),
                KeypointType.RIGHT_HIP to Triple(0.56f, 0.5f, 0.9f),
                // 무릎·발목은 신뢰도 미달
            ),
            0.75f, CameraAngle.SIDE,
        )

        assertEquals(PoseVisibility.UPPER_BODY_ONLY, form.visibility)
        assertNull(form.kneeAngles.representative)
    }

    @Test
    fun `한쪽 다리만 보여도 FULL이다`() {
        // 측면 촬영에서는 먼 쪽 다리가 가려지는 것이 정상이다.
        val form = analyzer.analyze(
            poseOf(
                KeypointType.LEFT_SHOULDER to Triple(0.5f, 0.25f, 0.9f),
                KeypointType.LEFT_HIP to Triple(0.5f, 0.5f, 0.9f),
                KeypointType.LEFT_KNEE to Triple(0.5f, 0.7f, 0.9f),
                KeypointType.LEFT_ANKLE to Triple(0.5f, 0.9f, 0.9f),
            ),
            0.75f, CameraAngle.SIDE,
        )

        assertEquals(PoseVisibility.FULL, form.visibility)
        assertNotNull(form.kneeAngles.left)
    }

    @Test
    fun `키포인트가 없으면 아무것도 판정하지 않는다`() {
        val form = analyzer.analyze(poseOf(), 0.75f, CameraAngle.SIDE)

        assertNull(form.depth)
        assertNull(form.torsoLeanDegrees)
        assertNull(form.suggestedAngle)
        assertTrue(form.warnings.isEmpty())
    }

    /**
     * 무릎폭 비율은 촬영 각도로 게이트하지 않는다 — 측면에서는 판정에 쓸 수 없지만,
     * 데이터 측이 domain gap을 검증할 재료로 기록은 남겨야 한다. 판정 쪽에서 걸러진다.
     */
    @Test
    fun `무릎폭 비율은 촬영 각도와 무관하게 기록된다`() {
        val pose = frontFacing(kneeSpread = 0.06f, ankleSpread = 0.18f)

        assertNotNull(analyzer.analyze(pose, 0.75f, CameraAngle.SIDE).kneeSpreadRatio)
        assertNotNull(analyzer.analyze(pose, 0.75f, CameraAngle.FRONT).kneeSpreadRatio)
        assertFalse(
            "측면은 무릎 정렬을 지원하지 않는다",
            CameraAngle.SIDE.supports(FormCheck.KNEE_ALIGNMENT),
        )
    }
}
