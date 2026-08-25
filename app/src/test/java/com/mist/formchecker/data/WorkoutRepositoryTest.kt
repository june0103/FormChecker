package com.mist.formchecker.data

import com.mist.formchecker.data.local.PerformanceMetricsEntity
import com.mist.formchecker.data.local.RepRecordEntity
import com.mist.formchecker.data.local.SessionListItem
import com.mist.formchecker.data.local.SessionRepCount
import com.mist.formchecker.data.local.WorkoutDao
import com.mist.formchecker.data.local.WorkoutSessionEntity
import com.mist.formchecker.data.local.WorkoutSetEntity
import com.mist.formchecker.poseengine.RepScore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 저장된 행에 **어느 기준으로 판정했는지**가 들어가는가.
 *
 * ## 왜 이걸 테스트하나
 * 판정 기준이 rep마다 다를 수 있는데(설정의 기준 완화, 개발 화면의 허용폭 노브) 그것이
 * 행에 남지 않으면 **나중에 임계값을 재분석할 수 없다.** 값을 넘기는 자리와 저장하는
 * 자리가 떨어져 있어(ViewModel → Repository → Entity) 중간에서 조용히 빠져도 화면에는
 * 아무 티가 나지 않는다.
 *
 * Room을 띄우지 않고 [WorkoutDao]를 가짜로 대신한다 — 확인하려는 것은 SQL이 아니라
 * **저장소가 무엇을 행으로 만드는가**다.
 */
class WorkoutRepositoryTest {

    private class RecordingDao : WorkoutDao {
        var session: WorkoutSessionEntity? = null
        var reps: List<RepRecordEntity> = emptyList()

        override suspend fun upsertSession(session: WorkoutSessionEntity) {
            this.session = session
        }

        override suspend fun upsertSet(set: WorkoutSetEntity) = Unit

        override suspend fun upsertReps(reps: List<RepRecordEntity>) {
            this.reps = reps
        }

        override suspend fun upsertMetrics(metrics: PerformanceMetricsEntity) = Unit
        override suspend fun session(sessionId: String): WorkoutSessionEntity? = session
        override fun repsOf(sessionId: String): Flow<List<RepRecordEntity>> = flowOf(reps)
        override fun metricsOf(sessionId: String): Flow<PerformanceMetricsEntity?> = flowOf(null)
        override fun sessionList(): Flow<List<SessionListItem>> = flowOf(emptyList())
        override fun sessionRepCountsBetween(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<SessionRepCount>> = flowOf(emptyList())

        override fun sessionCount(): Flow<Int> = flowOf(0)
        override fun pendingTotal(): Flow<Int> = flowOf(0)
        override suspend fun deleteSession(sessionId: String) = Unit
    }

    private fun repInput(
        number: Int,
        relaxed: Boolean,
        kneeTolerance: Float,
        shallowTolerance: Float,
    ) = CompletedRepInput(
        repNumber = number,
        recordedAtMs = 1_000L * number,
        durationMs = 1_700L,
        cameraAngle = "FRONT",
        score = RepScore(
            depthScore = null,
            checkedCount = 2,
            warnedCount = 0,
            errorFlags = emptyMap(),
        ),
        depthLevel = "PARALLEL",
        depthBasis = "LEG_LENGTH",
        depthRatio = 0.02f,
        minKneeAngle = 88f,
        maxTorsoLeanDegrees = 30f,
        validFrameRatio = 0.9f,
        kneeToeSide = null,
        warningPhases = emptyMap(),
        relaxedForm = relaxed,
        kneeTolerance = kneeTolerance,
        shallowTolerance = shallowTolerance,
    )

    private suspend fun save(dao: RecordingDao, reps: List<CompletedRepInput>, thresholds: String?) =
        WorkoutRepository(dao).saveSession(
            startedAtMs = 0L,
            endedAtMs = 10_000L,
            deviceModel = null,
            osVersion = null,
            poseModel = "RTMPose 26",
            reps = reps,
            metrics = null,
            formThresholds = thresholds,
        )

    @Test
    fun `판정 기준이 rep 행에 저장된다`() = runTest {
        val dao = RecordingDao()
        save(dao, listOf(repInput(1, relaxed = true, kneeTolerance = 0.15f, shallowTolerance = 0.10f)), null)

        val row = dao.reps.single()
        assertEquals(true, row.relaxedForm)
        assertEquals(0.15f, row.kneeTolerance)
        assertEquals(0.10f, row.shallowTolerance)
    }

    /**
     * **세션 도중에 기준이 바뀔 수 있다.** 개발 화면의 노브가 그렇고, 설정을 바꿔도
     * 그렇다. 세션 단위로 한 번만 저장하면 그 사실이 사라진다.
     */
    @Test
    fun `rep마다 다른 기준이 각각 남는다`() = runTest {
        val dao = RecordingDao()
        save(
            dao,
            listOf(
                repInput(1, relaxed = false, kneeTolerance = 0.10f, shallowTolerance = 0.03f),
                repInput(2, relaxed = true, kneeTolerance = 0.15f, shallowTolerance = 0.10f),
            ),
            null,
        )

        assertEquals(listOf(false, true), dao.reps.map { it.relaxedForm })
        assertEquals(listOf(0.10f, 0.15f), dao.reps.map { it.kneeTolerance })
    }

    /**
     * 나머지 임계값은 세션에 한 번만 남긴다 — rep마다 같은 값을 반복해 저장할 이유가 없다.
     * 형식을 정하지 않고 `toString()`을 담으므로, 임계값이 늘면 저절로 함께 남는다.
     */
    @Test
    fun `세션에 임계값 스냅샷이 남는다`() = runTest {
        val dao = RecordingDao()
        val snapshot = com.mist.formchecker.poseengine.FormThresholds().toString()
        save(dao, listOf(repInput(1, false, 0.10f, 0.03f)), snapshot)

        val saved = dao.session?.formThresholds
        assertNotNull(saved)
        assertTrue("임계값 이름이 그대로 남아야 한다", saved!!.contains("kneeToeToleranceRatio"))
        assertTrue(saved.contains("heelRiseLimit"))
    }

    /** 기록하지 않던 시절과 구분되도록, 스냅샷이 없으면 null이다. */
    @Test
    fun `스냅샷이 없으면 null이다`() = runTest {
        val dao = RecordingDao()
        save(dao, listOf(repInput(1, false, 0.10f, 0.03f)), null)
        assertEquals(null, dao.session?.formThresholds)
    }
}
