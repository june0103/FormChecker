package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세션 내 이상치 판정.
 *
 * ## 왜 이걸 테스트하는가
 * 이 판정이 `include_in_reference`를 정하므로, 틀리면 **오염된 rep이 정상 범위의 재료가
 * 된다.** 그리고 임계값은 한 번 만들어지면 그 뒤 모든 판정의 기준이라 조용히 틀린다.
 */
class RepOutliersTest {

    /**
     * rep 하나. 비교에 쓰이는 값만 인자로 받고 나머지는 통과하는 기본값을 둔다.
     *
     * `counted = true`, `formEvaluable = true`, `validFrameRatio = 1f`이어야 판정의
     * 기준 표본(basis)에 들어간다.
     */
    private fun rep(
        id: Int,
        durationMs: Long = 2000L,
        maxKneeFlexion: Float = 110f,
        maxDepthRatio: Float = 0.05f,
        minThighAngle: Float = 2f,
        maxTrunkLean: Float = 35f,
        frameCount: Int = 50,
    ) = CaptureRep(
        repId = id,
        counted = true,
        formEvaluable = true,
        startMs = 0L,
        bottomMs = durationMs / 2,
        endMs = durationMs,
        descentDurationMs = durationMs / 2,
        ascentDurationMs = durationMs / 2,
        maxKneeFlexion = maxKneeFlexion,
        maxHipFlexion = 100f,
        maxAnkleDorsiflexion = 20f,
        maxDepthRatio = maxDepthRatio,
        minThighAngle = minThighAngle,
        maxTrunkLean = maxTrunkLean,
        maxHeelRise = 0.01f,
        maxLeftMedialKnee = 0.02f,
        maxRightMedialKnee = 0.02f,
        maxHipShift = 0.03f,
        validFrameRatio = 1f,
        frameCount = frameCount,
        intent = CaptureIntent.NORMAL,
        frameIntervalMs = 33f,
        maxFrameIntervalMs = 40L,
        bottomDwellMs = 250L,
    )

    /** 값이 조금씩 흩어진 정상 세션. MAD 스케일이 0이 되지 않게 한다. */
    private fun baseSession(count: Int = 12) = List(count) { i ->
        rep(id = i + 1, durationMs = 2000L + (i % 4) * 40L, maxKneeFlexion = 110f + (i % 3))
    }

    /**
     * `frame_count`를 특징에서 뺀 이유의 회귀 테스트.
     *
     * 프레임 수는 동작 특징이 아니라 샘플링 부산물이다. RTMPose 연속 추론에 발열
     * 스로틀링이 걸려 fps가 세션 안에서 떨어지므로(실측 26.8 → 19.2), 같은 동작이라도
     * 세션 앞쪽 rep이 프레임을 더 많이 갖는다. 그걸로 이상치를 판정하면 **"그때 기기가
     * 시원했다"를 "동작이 달랐다"로 읽는다.**
     */
    @Test
    fun `프레임 수만 다른 rep은 이상치가 아니다`() {
        // 앞쪽 4개가 fps가 높아 프레임이 1.6배 많다. 지속시간·각도는 나머지와 같다.
        val reps = baseSession().mapIndexed { index, base ->
            if (index < 4) base.copy(frameCount = 80) else base
        }
        val deviations = RepOutliers.deviations(reps)

        val flagged = reps.filter { RepOutliers.isOutlier(deviations[it.repId]) }
        assertTrue(
            "프레임 수 차이로 걸린 rep이 있다: ${flagged.map { it.repId to deviations[it.repId] }}",
            flagged.isEmpty(),
        )
    }

    /** 실제로 느린 rep은 여전히 걸려야 한다. 특징을 뺀 것이 판정을 무력화하면 안 된다. */
    @Test
    fun `지속시간이 크게 다른 rep은 걸린다`() {
        val reps = baseSession() + rep(id = 99, durationMs = 7263L)
        val deviations = RepOutliers.deviations(reps)

        assertTrue(
            "느린 rep이 걸려야 한다 (편차: ${deviations[99]})",
            RepOutliers.isOutlier(deviations[99]),
        )
        val others = reps.filter { it.repId != 99 }
        assertTrue(
            "나머지는 걸리지 않아야 한다",
            others.none { RepOutliers.isOutlier(deviations[it.repId]) },
        )
    }

    /** 깊이가 얕은 rep. 시간은 같아도 동작이 다르면 걸려야 한다. */
    @Test
    fun `깊이가 크게 다른 rep은 걸린다`() {
        val reps = baseSession() + rep(id = 99, maxKneeFlexion = 70f, maxDepthRatio = -0.20f)
        val deviations = RepOutliers.deviations(reps)
        assertTrue("얕은 rep이 걸려야 한다 (편차: ${deviations[99]})", RepOutliers.isOutlier(deviations[99]))
    }

    /**
     * 표본이 적으면 중앙값 자체가 불안정해 "다르다"는 판단이 무의미해진다. 그때 배제하는
     * 것이 더 나쁘다 — 판단 근거가 없는데 데이터를 버리는 것이다.
     */
    @Test
    fun `표본이 적으면 판정하지 않는다`() {
        // 크게 벗어난 rep까지 합쳐 MIN_SAMPLES 하나 아래로 맞춘다.
        val reps = baseSession(count = RepOutliers.MIN_SAMPLES - 2) + rep(id = 99, durationMs = 7263L)
        assertEquals(RepOutliers.MIN_SAMPLES - 1, reps.size)

        val deviations = RepOutliers.deviations(reps)
        assertTrue("편차를 내지 않아야 한다", deviations.isEmpty())
        assertFalse("이상치로도 보지 않아야 한다", RepOutliers.isOutlier(deviations[99]))

        // 하나만 더 있으면 같은 rep이 걸린다 — 경계가 표본 수라는 것을 고정한다.
        val enough = reps + rep(id = 98)
        assertTrue(
            "MIN_SAMPLES를 채우면 판정이 돌아야 한다",
            RepOutliers.isOutlier(RepOutliers.deviations(enough)[99]),
        )
    }

    /**
     * 중단된 rep은 애초에 동작이 다르므로 중앙값을 오염시킨다. 기준에서 빼되, 그 rep의
     * 편차 자체는 계산해 둔다 — 왜 제외됐는지 숫자로 남아야 한다.
     */
    @Test
    fun `중단된 rep은 기준에서 빼지만 편차는 낸다`() {
        val aborted = List(6) { rep(id = 100 + it, durationMs = 400L).copy(counted = false) }
        val reps = baseSession() + aborted
        val deviations = RepOutliers.deviations(reps)

        val normal = baseSession().mapNotNull { deviations[it.repId] }
        assertTrue("정상 rep은 걸리지 않아야 한다", normal.none { it > RepOutliers.THRESHOLD })
        assertTrue("중단된 rep의 편차도 계산돼야 한다", deviations[100] != null)
        assertTrue("중단된 rep은 크게 벗어나야 한다", RepOutliers.isOutlier(deviations[100]))
    }

    /**
     * 모든 값이 똑같으면 MAD가 0이 되고 어떤 차이든 무한이 된다. 그 특징은 쓸 수 없다.
     */
    @Test
    fun `값이 모두 같은 특징은 무시한다`() {
        val identical = List(12) { rep(id = it + 1) }
        val deviations = RepOutliers.deviations(identical)

        assertTrue(
            "완전히 동일한 세션에서 이상치가 나오면 안 된다",
            identical.none { RepOutliers.isOutlier(deviations[it.repId]) },
        )
    }

    /** 편차는 rep id로 돌려준다. 순서에 의존하면 목록이 바뀔 때 조용히 어긋난다. */
    @Test
    fun `편차를 rep id로 돌려준다`() {
        val reps = baseSession()
        val deviations = RepOutliers.deviations(reps)
        assertEquals(reps.map { it.repId }.toSet(), deviations.keys)
    }
}
