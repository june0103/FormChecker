package com.mist.formchecker.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 세션 목록 한 줄에 필요한 것.
 *
 * 세션 하나에 rep이 수십 개라 목록에서 전부 읽으면 낭비다. 집계만 SQL로 뽑는다 —
 * `CaptureLibrary`가 `frames.csv`를 읽지 않는 것과 같은 이유다.
 */
data class SessionListItem(
    @Embedded val session: WorkoutSessionEntity,
    val repCount: Int,
    /** 경고가 하나라도 있던 rep 수. */
    val warnedRepCount: Int,
    /** 아직 서버로 안 보낸 행 수(세션 자신 + 그 아래 rep). 기록 화면의 pending 배지 재료. */
    val pendingCount: Int,
)

@Dao
interface WorkoutDao {

    // ── 쓰기 ────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: WorkoutSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSet(set: WorkoutSetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReps(reps: List<RepRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetrics(metrics: PerformanceMetricsEntity)

    /**
     * 세션 하나를 통째로 저장한다.
     *
     * **트랜잭션이어야 한다** — rep은 `set_id`를, 세트는 `session_id`를 참조하므로 중간에
     * 실패하면 FK가 가리킬 부모가 없는 행이 남거나(부모 먼저 실패) rep 없는 세션이 기록에
     * 뜬다. 운동이 끝나는 시점은 앱이 백그라운드로 가거나 프로세스가 죽기 쉬운 순간이다.
     */
    @Transaction
    suspend fun saveCompletedSession(
        session: WorkoutSessionEntity,
        set: WorkoutSetEntity,
        reps: List<RepRecordEntity>,
        metrics: PerformanceMetricsEntity?,
    ) {
        upsertSession(session)
        upsertSet(set)
        if (reps.isNotEmpty()) upsertReps(reps)
        metrics?.let { upsertMetrics(it) }
    }

    // ── 읽기 ────────────────────────────────────────────────

    @Query("SELECT * FROM workout_sessions WHERE id = :sessionId")
    suspend fun session(sessionId: String): WorkoutSessionEntity?

    @Query(
        """
        SELECT r.* FROM rep_records r
        JOIN workout_sets s ON r.set_id = s.id
        WHERE s.session_id = :sessionId
        ORDER BY r.rep_number
        """,
    )
    fun repsOf(sessionId: String): Flow<List<RepRecordEntity>>

    @Query("SELECT * FROM performance_metrics WHERE session_id = :sessionId LIMIT 1")
    fun metricsOf(sessionId: String): Flow<PerformanceMetricsEntity?>

    /**
     * 기록 화면용 목록. 최근 세션이 먼저다.
     *
     * `warned_count > 0`을 SQL에서 세는 이유: rep 행을 앱으로 끌어와 세면 세션 20개 ×
     * rep 30개 = 600행을 목록 표시에만 읽는다.
     */
    @Query(
        """
        SELECT
          sess.*,
          (SELECT COUNT(*) FROM rep_records r
             JOIN workout_sets s ON r.set_id = s.id
            WHERE s.session_id = sess.id) AS repCount,
          (SELECT COUNT(*) FROM rep_records r
             JOIN workout_sets s ON r.set_id = s.id
            WHERE s.session_id = sess.id AND r.warned_count > 0) AS warnedRepCount,
          (CASE WHEN sess.sync_status = 'SYNCED' THEN 0 ELSE 1 END
           + (SELECT COUNT(*) FROM rep_records r
                JOIN workout_sets s ON r.set_id = s.id
               WHERE s.session_id = sess.id AND r.sync_status != 'SYNCED')) AS pendingCount
        FROM workout_sessions sess
        ORDER BY sess.started_at DESC
        """,
    )
    fun sessionList(): Flow<List<SessionListItem>>

    /** 기록 화면 상단 배지. 세션 전체에서 안 보낸 행 수. */
    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM workout_sessions WHERE sync_status != 'SYNCED')
        + (SELECT COUNT(*) FROM rep_records WHERE sync_status != 'SYNCED')
        + (SELECT COUNT(*) FROM performance_metrics WHERE sync_status != 'SYNCED')
        """,
    )
    fun pendingTotal(): Flow<Int>

    @Query("DELETE FROM workout_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
