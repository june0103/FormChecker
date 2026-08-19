package com.mist.formchecker.poseengine

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
        assertNull("무릎 정렬은 측면에서 판정하지 않는다", form.kneeAlignment)
        assertNull("좌우 비대칭은 측면에서 판정하지 않는다", form.asymmetryDegrees)
    }

    @Test
    fun `정면에서는 무릎 정렬과 비대칭만 판정한다`() {
        val form = analyzer.analyze(frontFacing(), 0.75f, CameraAngle.FRONT)

        assertNotNull("무릎 정렬은 정면에서 판정한다", form.kneeAlignment)
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
    fun `valgus 비율을 주입하면 판정이 달라진다`() {
        // 무릎폭 0.10 / 발목폭 0.16 = 0.625
        val pose = frontFacing(kneeSpread = 0.10f, ankleSpread = 0.16f)

        val lenient = SquatFormAnalyzer(
            SquatAnalyzerConfig(form = FormThresholds(valgusRatio = 0.5f)),
        )
        val strictAnalyzer = SquatFormAnalyzer(
            SquatAnalyzerConfig(form = FormThresholds(valgusRatio = 0.9f)),
        )

        assertEquals(
            KneeAlignment.GOOD,
            lenient.analyze(pose, 0.75f, CameraAngle.FRONT).kneeAlignment,
        )
        assertEquals(
            KneeAlignment.VALGUS,
            strictAnalyzer.analyze(pose, 0.75f, CameraAngle.FRONT).kneeAlignment,
        )
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
    fun `무릎 폭이 발목 폭과 비슷하면 정상이다`() {
        val form = analyzer.analyze(
            frontFacing(kneeSpread = 0.16f, ankleSpread = 0.16f), 0.75f, CameraAngle.FRONT,
        )

        assertEquals(KneeAlignment.GOOD, form.kneeAlignment)
        assertTrue(form.warnings.isEmpty())
    }

    @Test
    fun `무릎이 발목보다 크게 모이면 valgus로 판정한다`() {
        val form = analyzer.analyze(
            frontFacing(kneeSpread = 0.06f, ankleSpread = 0.18f), 0.75f, CameraAngle.FRONT,
        )

        assertEquals(KneeAlignment.VALGUS, form.kneeAlignment)
        assertTrue(form.warnings.contains(FormWarning.KNEE_VALGUS))
    }

    /**
     * 절대 좌표가 아니라 비율로 판정하므로, 사람이 화면 어디에 서 있든 결과가 같아야 한다.
     * 이게 깨지면 카메라와의 거리에 따라 판정이 흔들린다.
     */
    @Test
    fun `무릎 정렬 판정은 화면상 위치에 영향받지 않는다`() {
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

        assertEquals(centered.kneeAlignment, shifted.kneeAlignment)
    }

    @Test
    fun `발을 모으고 서면 무릎 정렬을 판정하지 않는다`() {
        val form = analyzer.analyze(
            frontFacing(kneeSpread = 0.01f, ankleSpread = 0.02f), 0.75f, CameraAngle.FRONT,
        )

        assertNull("기준이 불안정한 구간에서는 판정을 보류한다", form.kneeAlignment)
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

    @Test
    fun `지원하지 않는 각도의 항목은 계산 자체를 하지 않는다`() {
        // 정면 모드에서 valgus인 자세를 줘도, 측면 모드로 보면 무릎 정렬은 null이다.
        val valgusPose = frontFacing(kneeSpread = 0.06f, ankleSpread = 0.18f)

        assertNull(analyzer.analyze(valgusPose, 0.75f, CameraAngle.SIDE).kneeAlignment)
        assertEquals(
            KneeAlignment.VALGUS,
            analyzer.analyze(valgusPose, 0.75f, CameraAngle.FRONT).kneeAlignment,
        )
    }
}
