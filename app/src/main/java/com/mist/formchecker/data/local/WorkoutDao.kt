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
     *
     * ## 상관 서브쿼리를 쓰지 않는다
     * 예전에는 세 값을 각각 서브쿼리로 셌다. 그러면 **세션 한 줄마다 서브쿼리가 3번**
     * 돌아서, 세션이 N개일 때 3N번 실행된다 — SQL로 쓰인 N+1이다. 지금은 조인 한 번을
     * `GROUP BY`로 접어 **rep 테이블을 한 번만 지나간다.**
     *
     * 실측(Galaxy S24 Ultra · Android 16 · `DbQueryBenchmark`, **p95**): 세션 2,000개 ·
     * rep 40,000개에서 **20.9ms → 15.7ms**, 세션 100개부터 2,000개까지 일관되게 1.2~1.3배다.
     * **극적인 차이는 아니다** — `workout_sets.session_id`와 `rep_records.set_id`에 이미
     * 인덱스가 있어 서브쿼리도 매번 인덱스 탐색이었기 때문이다.
     *
     * rep을 미리 세션별로 집계해 조인하는 판(파생 테이블)도 재봤는데 **같은 속도**였다
     * (16.0ms). SQL만 길어져서 버렸다.
     *
     * ## `COUNT(*)`가 아니라 `COUNT(r.id)`인 이유
     * `LEFT JOIN`이라 **rep이 하나도 없는 세션도 한 줄로 남는데**, 그 줄은 rep 쪽이 전부
     * NULL이다. `COUNT(*)`는 그 줄을 세어 **0이어야 할 곳에 1을 낸다.** `COUNT(r.id)`는
     * NULL을 세지 않아 0이 된다. `SUM`도 같은 이유로 `COALESCE`가 필요하다 — 더할 것이
     * 없으면 0이 아니라 NULL이다.
     */
    @Query(
        """
        SELECT
          sess.*,
          COUNT(r.id) AS repCount,
          COALESCE(SUM(CASE WHEN r.warned_count > 0 THEN 1 ELSE 0 END), 0) AS warnedRepCount,
          (CASE WHEN sess.sync_status = 'SYNCED' THEN 0 ELSE 1 END)
            + COALESCE(SUM(CASE WHEN r.sync_status != 'SYNCED' THEN 1 ELSE 0 END), 0) AS pendingCount
        FROM workout_sessions sess
        LEFT JOIN workout_sets s ON s.session_id = sess.id
        LEFT JOIN rep_records r ON r.set_id = s.id
        GROUP BY sess.id
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
     *
     * **앱을 켤 때마다 부르는 쿼리다.** 성능은 `started_at` 인덱스에서 나온다
     * ([WorkoutSessionEntity]) — 없으면 이번 주 몇 줄을 찾으려고 세션 테이블 전체를 훑는다.
     *
     * ## 여기서는 상관 서브쿼리를 그대로 둔다 (실측으로 확인)
     * [sessionList]는 서브쿼리를 조인 + `GROUP BY`로 바꿔 빨라졌는데, **같은 변경을 여기
     * 적용했더니 3배 느려졌다.** `GROUP BY sess.id`가 SQLite로 하여금 `started_at` 범위
     * 탐색을 버리고 PK 순서로 전체를 훑게 만들기 때문이다:
     *
     * ```
     * 서브쿼리:  SEARCH sess USING INDEX index_workout_sessions_started_at (started_at>? AND <?)
     * GROUP BY:  SCAN sess USING INDEX sqlite_autoindex_workout_sessions_1
     * ```
     *
     * 그리고 **여기서는 N+1이 문제가 아니다** — `WHERE`가 한 주로 자르므로 서브쿼리가
     * 도는 횟수가 **7 남짓으로 고정**이다. [sessionList]는 세션 전체가 대상이라 N이
     * 무한정 늘어난다. **같은 패턴이라도 N의 상한이 다르면 답이 달라진다.**
     *
     * 실측(세션 2,000개, **p95**): 서브쿼리 **0.10ms** vs `GROUP BY` **0.33ms**.
     * 인덱스 효과는 이쪽이다 — 인덱스 없이 같은 서브쿼리가 **0.20ms**이고,
     * 세션이 늘수록 격차가 벌어진다(500개 1.1배 → 2,000개 2.0배).
     *
     * 근거는 `DbQueryBenchmark.홈_주간집계_부하곡선`.
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
