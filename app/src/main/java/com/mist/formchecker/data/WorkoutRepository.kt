package com.mist.formchecker.data

import com.mist.formchecker.data.local.ErrorFlags
import com.mist.formchecker.data.local.PerformanceMetricsEntity
import com.mist.formchecker.data.local.RepRecordEntity
import com.mist.formchecker.data.local.SessionListItem
import com.mist.formchecker.data.local.SessionRepCount
import com.mist.formchecker.data.local.WorkoutDao
import com.mist.formchecker.data.local.WorkoutSessionEntity
import com.mist.formchecker.data.local.WorkoutSetEntity
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.RepPhase
import com.mist.formchecker.poseengine.RepScore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 완료한 rep 하나. ViewModel이 운동 중에 모아 두고 종료 시 한 번에 넘긴다.
 *
 * `RepSummary`(pose-engine)를 그대로 쓰지 않는 이유: 그건 좌표까지 담은 원재료라 저장
 * 계층까지 끌고 오면 DB가 알 필요 없는 것을 알게 된다. 여기서는 저장할 값만 받는다.
 */
data class CompletedRepInput(
    val repNumber: Int,
    val recordedAtMs: Long,
    val durationMs: Long,
    val cameraAngle: String,
    val score: RepScore,
    val depthLevel: String?,
    val depthBasis: String?,
    val depthRatio: Float?,
    val minKneeAngle: Float?,
    val maxTorsoLeanDegrees: Float?,
    val validFrameRatio: Float,
    /** 무릎 편차가 가장 컸던 쪽. 사람 기준 좌/우. */
    val kneeToeSide: String?,
    /** 경고 종류별로 문제가 났던 구간. */
    val warningPhases: Map<FormWarning, Set<RepPhase>>,
    /**
     * 이 rep을 판정할 때 쓴 기준. **rep마다 다를 수 있다** — 설정의 기준 완화와 개발
     * 화면의 허용폭 노브가 세션 도중에도 바뀐다(`RepRecordEntity`).
     */
    val relaxedForm: Boolean,
    val kneeTolerance: Float,
    val shallowTolerance: Float,
)

/** 세션 성능 지표. 없으면(프레임을 한 장도 못 돌린 경우) null로 넘긴다. */
data class SessionMetricsInput(
    val modelLoadMs: Long?,
    val avgInferenceMs: Float?,
    val p95InferenceMs: Float?,
    val droppedFrameRatio: Float?,
    val avgFps: Float?,
    val delegateType: String?,
    val analyzedFrameCount: Int,
)

/**
 * 운동 기록 저장소. **Room이 1차 저장소다** (설계문서 195행).
 *
 * Supabase 동기화는 아직 없다. 모든 행이 `sync_status = PENDING`으로 들어가고, 기록 화면이
 * 그 수를 배지로 보여준다 — 동기화가 붙기 전에도 "아직 안 보낸 게 N건"이라는 상태가
 * 사용자에게 정직하게 드러난다.
 */
@Singleton
class WorkoutRepository @Inject constructor(
    private val dao: WorkoutDao,
) {
    /**
     * 세션 하나를 저장하고 그 id를 돌려준다.
     *
     * id를 클라이언트에서 만드는 이유: 오프라인 큐가 재전송해도 upsert로 멱등 처리되려면
     * 서버가 아니라 클라이언트가 키를 정해야 한다 (설계문서 4장).
     *
     * rep이 0개인 세션은 **여기까지 오지 않는다.** 운동 종료 버튼이 그 전에 걸러낸다
     * ([WorkoutViewModel.finishSession]) — 사용자에게 0회 세션은 기록이 아니라 잘못 누른
     * 흔적이라서다. 저장소는 그 판단을 하지 않는다: 부르면 그대로 저장한다.
     */
    suspend fun saveSession(
        startedAtMs: Long,
        endedAtMs: Long,
        deviceModel: String?,
        osVersion: String?,
        poseModel: String?,
        reps: List<CompletedRepInput>,
        metrics: SessionMetricsInput?,
        /** 이 세션이 쓴 임계값 전부. 형식은 정하지 않는다(`WorkoutSessionEntity`). */
        formThresholds: String?,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val setId = UUID.randomUUID().toString()

        dao.saveCompletedSession(
            session = WorkoutSessionEntity(
                id = sessionId,
                deviceModel = deviceModel,
                osVersion = osVersion,
                poseModel = poseModel,
                startedAt = startedAtMs,
                endedAt = endedAtMs,
                formThresholds = formThresholds,
            ),
            set = WorkoutSetEntity(
                id = setId,
                sessionId = sessionId,
                completedReps = reps.size,
            ),
            reps = reps.map { it.toEntity(setId) },
            metrics = metrics?.let {
                PerformanceMetricsEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    modelLoadMs = it.modelLoadMs,
                    avgInferenceMs = it.avgInferenceMs,
                    p95InferenceMs = it.p95InferenceMs,
                    droppedFrameRatio = it.droppedFrameRatio,
                    avgFps = it.avgFps,
                    delegateType = it.delegateType,
                    analyzedFrameCount = it.analyzedFrameCount,
                    createdAt = endedAtMs,
                )
            },
        )
        return sessionId
    }

    suspend fun session(sessionId: String) = dao.session(sessionId)
    fun reps(sessionId: String) = dao.repsOf(sessionId)
    fun metrics(sessionId: String) = dao.metricsOf(sessionId)
    fun sessions(): Flow<List<SessionListItem>> = dao.sessionList()
    fun pendingTotal(): Flow<Int> = dao.pendingTotal()

    /**
     * [fromMs, toMs) 안에 **시작한** 세션들의 횟수. 홈 화면 집계용.
     *
     * 경계를 호출부가 정한다 — 하루/한 주의 경계는 기기 시간대에 달렸고, 그 계산은
     * 테스트할 수 있는 자리에 있어야 한다(`weekWindowOf`).
     */
    fun sessionRepCounts(fromMs: Long, toMs: Long): Flow<List<SessionRepCount>> =
        dao.sessionRepCountsBetween(fromMs, toMs)

    /** 지금까지 저장된 세션 수. 0이면 "아직 기록이 하나도 없다". */
    fun sessionCount(): Flow<Int> = dao.sessionCount()
    suspend fun deleteSession(sessionId: String) = dao.deleteSession(sessionId)

    private fun CompletedRepInput.toEntity(setId: String) = RepRecordEntity(
        id = UUID.randomUUID().toString(),
        setId = setId,
        repNumber = repNumber,
        recordedAt = recordedAtMs,
        durationMs = durationMs,
        depthScore = score.depthScore,
        checkedCount = score.checkedCount,
        warnedCount = score.warnedCount,
        // 판정 항목이 하나도 없으면 이 rep으로는 자세를 말할 수 없다.
        isValid = score.checkedCount > 0,
        errorFlags = ErrorFlags.encode(score.errorFlags),
        cameraAngle = cameraAngle,
        depthLevel = depthLevel,
        depthBasis = depthBasis,
        depthRatio = depthRatio,
        minKneeAngle = minKneeAngle,
        maxTorsoLeanDegrees = maxTorsoLeanDegrees,
        validFrameRatio = validFrameRatio,
        kneeToeSide = kneeToeSide,
        warningPhases = ErrorFlags.encodePhases(warningPhases),
        relaxedForm = relaxedForm,
        kneeTolerance = kneeTolerance,
        shallowTolerance = shallowTolerance,
    )
}
