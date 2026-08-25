package com.mist.formchecker.data.local

import androidx.room.ColumnInfo
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

/**
 * 세션 하나의 시작 시각과 횟수. **홈 화면 집계 재료다.**
 *
 * 날짜별로 묶는 일을 SQL에서 하지 않는 이유: 하루의 경계는 **기기 시간대**에 달렸는데,
 * SQLite에 시간대를 넘기면 그 계산이 쿼리 문자열 안에 숨어 테스트할 수 없다. 대신 주
 * 범위만 SQL로 자르고(한 주면 세션 수십 개), 날짜 버킷은 순수 함수가 나눈다.
 */
data class SessionRepCount(
    @ColumnInfo(name = "started_at") val startedAt: Long,
    val repCount: Int,
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

    /**
     * 주어진 기간에 시작한 세션의 횟수. 홈 화면의 오늘·이번 주 집계가 쓴다.
     *
     * **rep 행을 앱으로 끌어오지 않는다** — 기록이 쌓여도 읽는 양이 그 주의 세션 수로
     * 고정된다. 세션이 자정을 넘겨 이어져도 **시작 시각의 날짜**로 센다(한 세션이 두 날에
     * 쪼개지면 "그날 몇 회 했나"가 사용자가 기억하는 것과 어긋난다).
     */
    @Query(
        """
        SELECT
          sess.started_at AS started_at,
          (SELECT COUNT(*) FROM rep_records r
             JOIN workout_sets s ON r.set_id = s.id
            WHERE s.session_id = sess.id) AS repCount
        FROM workout_sessions sess
        WHERE sess.started_at >= :fromMs AND sess.started_at < :toMs
        ORDER BY sess.started_at
        """,
    )
    fun sessionRepCountsBetween(fromMs: Long, toMs: Long): Flow<List<SessionRepCount>>

    /** 기록이 하나라도 있나. "아직 아무것도 안 했다"와 "이번 주만 비었다"를 가른다. */
    @Query("SELECT COUNT(*) FROM workout_sessions")
    fun sessionCount(): Flow<Int>

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
