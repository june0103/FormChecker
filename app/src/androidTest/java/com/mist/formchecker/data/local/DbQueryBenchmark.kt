package com.mist.formchecker.data.local

import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * **기록이 쌓였을 때 목록·홈 쿼리가 얼마나 느려지는가**를 재는 부하 벤치마크.
 *
 * ## 왜 필요한가
 * 개발 중에는 세션이 열 개 남짓이라 어떤 쿼리를 써도 즉시 돌아온다. 문제는 **한 해 쓰면
 * 수백 세션**이 쌓인다는 것이고, 그때 느려지는지는 지금 재지 않으면 알 수 없다.
 * 사용자가 느려졌다고 말할 때는 이미 그 데이터가 그 사람 폰에만 있다.
 *
 * ## 이 파일이 재현성을 확보하는 방법
 * 라이브 데이터가 아니라 **합성 데이터를 정해진 규칙으로 심는다**. 같은 크기면 언제
 * 돌려도 같은 행이 만들어지므로, 측정값의 차이는 **후보 차이에서만** 온다.
 *
 * 후보들을 **한 번의 실행에서 나란히** 잰다. 코드를 되돌렸다 다시 적용하며 두 번 재면
 * 그 사이 기기 상태(온도·백그라운드 작업)가 달라져 비교가 흐려진다.
 *
 * ## 인덱스도 측정 대상이다
 * 인덱스를 엔티티에만 선언해두면 **"개선 전"에도 이미 인덱스가 깔린 DB에서 재게 된다** —
 * 그러면 인덱스 효과가 0으로 보인다(실제로 처음 이 실수를 했다). 그래서 측정 직전에
 * [setIndex]로 붙였다 떼며 잰다.
 *
 * ## 측정에서 제외한 것
 * Room의 객체 매핑을 거치지 않고 **커서를 끝까지 소비하는 시간**만 잰다. 매핑 비용은
 * 모든 후보가 같은 열을 내므로 동일하고, 여기서 보려는 것은 **질의 계획의 차이**다.
 *
 * ## 실행
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell am instrument -w \
 *   -e class com.mist.formchecker.data.local.DbQueryBenchmark \
 *   com.mist.formchecker.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s DbBench:I
 * ```
 * 결과는 logcat 태그 `DbBench`에 마크다운 표로 찍힌다.
 *
 * ## ⚠ `connectedAndroidTest`를 쓰지 말 것
 * `./gradlew connectedDebugAndroidTest`는 **테스트가 끝나면 앱을 제거한다**(AGP 기본
 * 동작). 앱 제거는 앱 전용 저장소를 통째로 지우므로 **기기에 쌓인 운동 기록이 전부
 * 사라진다.** 이 파일을 만들면서 실제로 그렇게 잃었다(2026-08-28,
 * `기술선택_기록.md` 94번).
 *
 * 위 방식은 설치와 실행을 나눠서 제거 단계가 없다. 에뮬레이터에서는 어느 쪽을 써도
 * 되지만, **실기기는 사람이 쓰는 기기다.**
 */
@RunWith(AndroidJUnit4::class)
class DbQueryBenchmark {

    private val db = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(),
        FormCheckerDatabase::class.java,
        "benchmark.db",
    ).build()

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase("benchmark.db")
    }

    // ── 후보 SQL ────────────────────────────────────────────────

    /** 기록 목록 · 세션 한 줄마다 상관 서브쿼리 3개. 세션이 N개면 3N번 실행된다. */
    private val listSubquery = """
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
    """

    /** 기록 목록 · 조인 한 번을 `GROUP BY`로 접는다. rep 테이블을 한 번만 지난다. */
    private val listGroupBy = """
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
    """

    /**
     * 기록 목록 · rep을 **먼저 세션별로 집계한 뒤** 조인한다.
     *
     * `GROUP BY sess.id`가 없으므로 바깥 쿼리가 `started_at` 인덱스 순서로 읽을 수 있어
     * 정렬용 임시 B-트리가 필요 없을지 보려고 넣은 후보다.
     */
    private val listDerived = """
        SELECT
          sess.*,
          COALESCE(agg.repCount, 0) AS repCount,
          COALESCE(agg.warnedRepCount, 0) AS warnedRepCount,
          (CASE WHEN sess.sync_status = 'SYNCED' THEN 0 ELSE 1 END)
            + COALESCE(agg.pendingReps, 0) AS pendingCount
        FROM workout_sessions sess
        LEFT JOIN (
          SELECT
            s.session_id AS sid,
            COUNT(r.id) AS repCount,
            SUM(CASE WHEN r.warned_count > 0 THEN 1 ELSE 0 END) AS warnedRepCount,
            SUM(CASE WHEN r.sync_status != 'SYNCED' THEN 1 ELSE 0 END) AS pendingReps
          FROM workout_sets s
          JOIN rep_records r ON r.set_id = s.id
          GROUP BY s.session_id
        ) agg ON agg.sid = sess.id
        ORDER BY sess.started_at DESC
    """

    /** 홈 주간 집계 · 상관 서브쿼리. `WHERE`가 한 주로 자르므로 서브쿼리는 7번 남짓 돈다. */
    private val weekSubquery = """
        SELECT
          sess.started_at AS started_at,
          (SELECT COUNT(*) FROM rep_records r
             JOIN workout_sets s ON r.set_id = s.id
            WHERE s.session_id = sess.id) AS repCount
        FROM workout_sessions sess
        WHERE sess.started_at >= ? AND sess.started_at < ?
        ORDER BY sess.started_at
    """

    /** 홈 주간 집계 · `GROUP BY` 판. **`started_at` 범위 탐색을 잃는다** — 표에서 확인. */
    private val weekGroupBy = """
        SELECT
          sess.started_at AS started_at,
          COUNT(r.id) AS repCount
        FROM workout_sessions sess
        LEFT JOIN workout_sets s ON s.session_id = sess.id
        LEFT JOIN rep_records r ON r.set_id = s.id
        WHERE sess.started_at >= ? AND sess.started_at < ?
        GROUP BY sess.id
        ORDER BY sess.started_at
    """

    /**
     * 기록 화면 상단의 "업로드 대기 N건" 배지. **세 테이블을 전부 훑는다.**
     *
     * 지금은 동기화가 없어 모든 행이 `PENDING`이지만, 서버가 붙으면 대부분 `SYNCED`가
     * 되고 **찾는 것은 소수**가 된다. 그때가 인덱스가 값을 하는 조건이다.
     */
    private val pendingBadge = """
        SELECT
          (SELECT COUNT(*) FROM workout_sessions WHERE sync_status != 'SYNCED')
        + (SELECT COUNT(*) FROM rep_records WHERE sync_status != 'SYNCED')
        + (SELECT COUNT(*) FROM performance_metrics WHERE sync_status != 'SYNCED')
    """

    /**
     * 동기화 워커가 보낼 rep을 집어오는 쿼리. **아직 없는 코드를 미리 재는 것이다.**
     *
     * WorkManager가 주기적으로 부를 자리이고, 한 번에 다 보내지 않고 배치로 끊는다.
     */
    private val syncBatch = """
        SELECT * FROM rep_records WHERE sync_status != 'SYNCED'
        ORDER BY recorded_at LIMIT 500
    """

    // ── 벤치마크 ────────────────────────────────────────────────

    @Test
    fun 기록목록_부하곡선() {
        val cases = listOf(
            Case("서브쿼리 · 인덱스✗", listSubquery, index = false),
            Case("서브쿼리 · 인덱스○", listSubquery, index = true),
            Case("**GROUP BY · 인덱스○**", listGroupBy, index = true),
            Case("파생조인 · 인덱스○", listDerived, index = true),
        )
        report("기록 목록 (sessionList)", measureAll(cases) { null })
    }

    @Test
    fun 홈_주간집계_부하곡선() {
        val cases = listOf(
            Case("서브쿼리 · 인덱스✗", weekSubquery, index = false),
            Case("**서브쿼리 · 인덱스○**", weekSubquery, index = true),
            Case("GROUP BY · 인덱스○", weekGroupBy, index = true),
        )
        report("홈 주간 집계 (sessionRepCountsBetween)", measureAll(cases) { size -> weekArgs(size) })
    }

    /**
     * **동기화가 붙은 뒤를 미리 재는 테스트.**
     *
     * 지금 이 앱에는 서버가 없어 모든 행이 `PENDING`이다. 그 상태로 재면 "안 보낸 것
     * 찾기"가 **전부 찾기**가 되어 인덱스가 소용없다 — 인덱스는 소수를 고를 때 값을 한다.
     *
     * 그래서 **동기화가 돈 뒤의 상태를 흉내 낸다**: 99%를 `SYNCED`로 바꾸고 1%만 남긴다.
     * 실제로 서버가 붙으면 이 비율이 정상이다(방금 만든 것 몇 개만 대기).
     */
    @Test
    fun 동기화_대기조회_부하곡선() {
        val badge = mutableListOf<Row>()
        val batch = mutableListOf<Row>()
        SIZES.forEach { size ->
            reseed(size)
            markMostlySynced()
            // **표를 나눠 낸다** — 한 표에 섞으면 배수가 전부 첫 열 기준이 되어
            // 워커배치가 배지와 비교된다. 서로 다른 쿼리라 그 배수는 뜻이 없다.
            badge += Row(
                size,
                listOf(
                    measureWithSyncIndex(false) { query(pendingBadge) },
                    measureWithSyncIndex(true) { query(pendingBadge) },
                ),
            ).also { it.labels = listOf("인덱스✗", "인덱스○") }
            batch += Row(
                size,
                listOf(
                    measureWithSyncIndex(false) { query(syncBatch) },
                    measureWithSyncIndex(true) { query(syncBatch) },
                ),
            ).also { it.labels = listOf("인덱스✗", "인덱스○") }
        }
        report("동기화 ① 업로드 대기 배지 (미전송 1% 가정)", badge)
        report("동기화 ② 워커 배치 조회 500건 (미전송 1% 가정)", batch)
    }

    private inline fun measureWithSyncIndex(enabled: Boolean, block: () -> Unit): Stats {
        setSyncIndex(enabled)
        return measure(block)
    }

    /**
     * 후보들이 **같은 답을 내는지** 확인한다.
     *
     * 빨라졌지만 값이 달라졌다면 개선이 아니라 버그다. 특히 `LEFT JOIN`은 **rep이 0개인
     * 세션**에서 조용히 틀리기 쉽다 — 조인 결과가 NULL 한 줄이라 `COUNT(*)`를 쓰면 0이
     * 아니라 1이 된다.
     */
    @Test
    fun 후보들이_같은_값을_낸다() {
        reseed(60)
        setIndex(true)
        assertSameRows(listSubquery, listGroupBy)
        assertSameRows(listSubquery, listDerived)
        assertSameRows(weekSubquery, weekGroupBy, weekArgs(60))
    }

    /** 질의 계획이 실제로 어떻게 갈리는지 — 추측이 아니라 SQLite가 말하게 한다. */
    @Test
    fun 질의계획을_출력한다() {
        reseed(2000)
        val args = weekArgs(2000)

        log("\n### EXPLAIN QUERY PLAN — 기록 목록 (세션 2,000)")
        setIndex(false)
        log("**서브쿼리 · 인덱스✗**\n${explain(listSubquery)}")
        setIndex(true)
        log("**서브쿼리 · 인덱스○**\n${explain(listSubquery)}")
        log("**GROUP BY · 인덱스○**\n${explain(listGroupBy)}")
        log("**파생조인 · 인덱스○**\n${explain(listDerived)}")

        log("\n### EXPLAIN QUERY PLAN — 홈 주간 집계 (세션 2,000)")
        setIndex(false)
        log("**서브쿼리 · 인덱스✗**\n${explain(weekSubquery, args)}")
        setIndex(true)
        log("**서브쿼리 · 인덱스○**\n${explain(weekSubquery, args)}")
        log("**GROUP BY · 인덱스○**\n${explain(weekGroupBy, args)}")
    }

    // ── 측정 골격 ───────────────────────────────────────────────

    private class Case(val label: String, val sql: String, val index: Boolean)

    private fun measureAll(cases: List<Case>, argsOf: (Int) -> Array<Any>?): List<Row> =
        SIZES.map { size ->
            reseed(size)
            val args = argsOf(size)
            Row(
                sessions = size,
                stats = cases.map { case ->
                    setIndex(case.index)
                    measure { query(case.sql, args) }
                },
            )
        }.also { rows -> rows.forEach { it.labels = cases.map(Case::label) } }

    /**
     * `started_at` 인덱스를 붙였다 뗀다.
     *
     * 엔티티에 선언된 인덱스는 DB를 만들 때 이미 생기므로, **"인덱스 없던 시절"을 재려면
     * 직접 지워야 한다.** 이름은 Room이 만드는 규칙과 같아야 다시 만들 때 충돌하지 않는다.
     */
    private fun setIndex(enabled: Boolean) {
        val sql = db.openHelper.writableDatabase
        if (enabled) {
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_workout_sessions_started_at` " +
                    "ON `workout_sessions` (`started_at`)",
            )
        } else {
            sql.execSQL("DROP INDEX IF EXISTS `index_workout_sessions_started_at`")
        }
    }

    /**
     * `sync_status` 인덱스를 붙였다 뗀다. **아직 엔티티에 없는 인덱스다** — 동기화가
     * 붙기 전에 넣을 값이 있는지 재보려고 여기서만 만든다.
     */
    private fun setSyncIndex(enabled: Boolean) {
        val sql = db.openHelper.writableDatabase
        if (enabled) {
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS `bench_rep_sync` ON `rep_records` (`sync_status`)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS `bench_session_sync` " +
                    "ON `workout_sessions` (`sync_status`)",
            )
        } else {
            sql.execSQL("DROP INDEX IF EXISTS `bench_rep_sync`")
            sql.execSQL("DROP INDEX IF EXISTS `bench_session_sync`")
        }
    }

    /**
     * 동기화가 한 번 돈 뒤를 흉내 낸다 — **99%를 `SYNCED`로** 바꾸고 1%만 남긴다.
     *
     * 전부 `PENDING`인 지금 상태로 재면 "안 보낸 것 찾기"가 전체 조회가 되어 인덱스
     * 효과가 나오지 않는다. 인덱스는 **소수를 고를 때** 값을 한다.
     */
    private fun markMostlySynced() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE workout_sessions SET sync_status = 'SYNCED'")
        sql.execSQL("UPDATE performance_metrics SET sync_status = 'SYNCED'")
        sql.execSQL("UPDATE rep_records SET sync_status = 'SYNCED'")
        // rep_number는 1~20이라 그것으로는 1%를 만들 수 없다. rowid로 100개마다 하나.
        sql.execSQL("UPDATE rep_records SET sync_status = 'PENDING' WHERE rowid % 100 = 0")
        // 가장 최근 세션 3개 — 크기와 무관하게 "방금 만든 것만 대기"가 된다.
        sql.execSQL(
            """
            UPDATE workout_sessions SET sync_status = 'PENDING' WHERE id IN
              (SELECT id FROM workout_sessions ORDER BY started_at DESC LIMIT 3)
            """,
        )
    }

    /** 가장 최근 한 주. 실제 홈 화면이 보는 범위와 같은 크기다. */
    private fun weekArgs(sessions: Int): Array<Any> {
        val to = BASE_TIME + sessions.toLong() * DAY_MS
        return arrayOf(to - 7 * DAY_MS, to)
    }

    // ── 합성 데이터 ─────────────────────────────────────────────

    /**
     * 세션 [sessions]개, 세션마다 rep [REPS_PER_SESSION]개를 심는다.
     *
     * 하루에 한 세션씩 배치한다 — 실제 사용 패턴과 같고, 주간 창 쿼리가 걸러낼 것이
     * 있어야 인덱스 효과가 드러난다.
     *
     * rep 5개마다 하나를 경고로, 3개마다 하나를 미전송으로 둔다. `warned_count`와
     * `sync_status`를 세는 집계가 **전부 0인 데이터에서는 비용이 드러나지 않는다.**
     */
    private fun reseed(sessions: Int) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM rep_records")
        sql.execSQL("DELETE FROM workout_sets")
        sql.execSQL("DELETE FROM workout_sessions")

        sql.beginTransaction()
        try {
            repeat(sessions) { i ->
                val sessionId = "s$i"
                val setId = "set$i"
                val startedAt = BASE_TIME + i.toLong() * DAY_MS
                sql.execSQL(
                    """
                    INSERT INTO workout_sessions
                      (id, exercise_type, device_model, os_version, pose_model,
                       started_at, ended_at, sync_status)
                    VALUES (?, 'squat', 'bench', '14', 'RTMPose 26', ?, ?, ?)
                    """,
                    arrayOf<Any>(sessionId, startedAt, startedAt + 300_000L, syncOf(i)),
                )
                sql.execSQL(
                    """
                    INSERT INTO workout_sets
                      (id, session_id, set_number, completed_reps, sync_status)
                    VALUES (?, ?, 1, ?, 'PENDING')
                    """,
                    arrayOf<Any>(setId, sessionId, REPS_PER_SESSION),
                )
                repeat(REPS_PER_SESSION) { j ->
                    sql.execSQL(
                        """
                        INSERT INTO rep_records
                          (id, set_id, rep_number, recorded_at, duration_ms,
                           checked_count, warned_count, is_valid, error_flags,
                           camera_angle, valid_frame_ratio, sync_status)
                        VALUES (?, ?, ?, ?, 1700, 5, ?, 1, '{}', 'FRONT', 0.9, ?)
                        """,
                        arrayOf<Any>(
                            "r$i-$j", setId, j + 1, startedAt + j * 3_000L,
                            if (j % 5 == 0) 1 else 0,
                            syncOf(j),
                        ),
                    )
                }
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private fun syncOf(i: Int) = if (i % 3 == 0) "PENDING" else "SYNCED"

    // ── 측정 도구 ───────────────────────────────────────────────

    /** 커서를 **끝까지 소비**한다. 지연 실행이라 끝까지 읽지 않으면 아무것도 재지 않는다. */
    private fun query(sql: String, args: Array<Any>? = null): Int {
        val cursor: Cursor = db.openHelper.readableDatabase.query(sql, args ?: emptyArray())
        cursor.use {
            var n = 0
            while (it.moveToNext()) n++
            return n
        }
    }

    /**
     * 한 후보의 실행 시간 분포. **대표값 하나로 줄이지 않는다.**
     *
     * @property p50 중앙값. "보통 이 정도"
     * @property p95 상위 5% 경계. **판단은 이 값으로 한다**
     * @property max 최악의 한 번
     */
    private class Stats(val p50: Double, val p95: Double, val max: Double)

    /**
     * [WARMUP]회 버린 뒤 [RUNS]회를 재서 **분포**를 낸다.
     *
     * ## 왜 평균도 중앙값도 아닌가
     * 평균은 백그라운드 작업이 한 번만 끼어들어도 통째로 흔들린다. 그래서 처음에는
     * 중앙값을 썼는데, **중앙값만 쓰는 것도 같은 종류의 실수**였다 — 절반의 실행이
     * 그보다 느리다는 사실이 숫자에서 사라진다.
     *
     * 사용자가 겪는 것은 평균적인 실행이 아니라 **느린 실행**이다. 기록 화면을 열 때
     * 중앙값이 16ms라도 p95가 40ms면 열 번에 한 번은 눈에 띄게 늦게 뜬다. 그래서
     * **p95를 판단 기준으로 두고 최댓값을 함께 적는다.**
     *
     * ## p95의 정밀도 한계
     * [RUNS]회 표본의 p95는 상위 몇 개 값에 좌우된다 — 정확한 분위수라기보다 **"느린
     * 쪽이 얼마나 느린가"의 지표**로 읽어야 한다. 표본을 크게 늘려도 기기 상태가
     * 변하는 시간이 함께 늘어나 더 정확해지지 않는다.
     *
     * 첫 실행을 버리는 이유: 페이지 캐시와 준비된 구문이 비어 있어 다른 것을 잰다.
     */
    private inline fun measure(block: () -> Unit): Stats {
        repeat(WARMUP) { block() }
        val times = DoubleArray(RUNS) { measureNanoTime { block() } / 1_000_000.0 }
        times.sort()
        // 최근접 순위법: p분위 = 올림(p × n)번째 값.
        val p95Index = (kotlin.math.ceil(0.95 * RUNS).toInt() - 1).coerceIn(0, RUNS - 1)
        return Stats(p50 = times[RUNS / 2], p95 = times[p95Index], max = times[RUNS - 1])
    }

    private fun explain(sql: String, args: Array<Any>? = null): String {
        val cursor = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $sql", args ?: emptyArray())
        return cursor.use {
            buildString {
                while (it.moveToNext()) {
                    append("    ").append(it.getString(it.columnCount - 1)).append('\n')
                }
            }
        }
    }

    private fun assertSameRows(a: String, b: String, args: Array<Any>? = null) {
        val left = dump(a, args)
        val right = dump(b, args)
        if (left != right) {
            throw AssertionError(
                "후보들이 다른 값을 냈다.\nA: ${left.take(5)}\nB: ${right.take(5)}",
            )
        }
    }

    private fun dump(sql: String, args: Array<Any>?): List<String> {
        val cursor = db.openHelper.readableDatabase.query(sql, args ?: emptyArray())
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add((0 until it.columnCount).joinToString("|") { c -> it.getString(c) ?: "∅" })
                }
            }
        }
    }

    private class Row(val sessions: Int, val stats: List<Stats>) {
        var labels: List<String> = emptyList()
    }

    /**
     * 표 두 개를 낸다.
     *
     * 1. **부하 곡선** — 크기별 p95. 세로로 읽으면 "커질 때 어떻게 되는가"가 보인다
     * 2. **가장 큰 크기의 분포** — p50 / p95 / 최대. 가로로 읽으면 "얼마나 흔들리는가"
     *
     * 한 표에 다 넣으면 칸마다 숫자가 셋이라 곡선 모양이 안 읽힌다. 곡선은 하나의
     * 지표로 그리고, 분포는 가장 아픈 지점에서만 펼친다.
     */
    private fun report(title: String, rows: List<Row>) {
        val labels = rows.first().labels
        log("\n### $title")
        log("세션당 rep ${REPS_PER_SESSION}개 · 웜업 ${WARMUP}회 후 ${RUNS}회 · 배수는 첫 열 대비\n")

        log("**부하 곡선 (p95)** — 판단은 이 값으로 한다\n")
        log("| 세션 | rep 총계 | " + labels.joinToString(" | ") + " |")
        log("|---|---|" + labels.joinToString("") { "---|" })
        rows.forEach { r ->
            val base = r.stats.first().p95
            val cells = r.stats.mapIndexed { i, s ->
                if (i == 0) "%.2f ms".format(s.p95)
                else "%.2f ms (%.1f배)".format(s.p95, if (s.p95 > 0) base / s.p95 else 0.0)
            }
            log(
                "| %,d | %,d | %s |".format(
                    r.sessions, r.sessions * REPS_PER_SESSION, cells.joinToString(" | "),
                ),
            )
        }

        val worst = rows.last()
        log("\n**분포 (세션 %,d개)** — 사용자가 겪는 것은 평균이 아니라 느린 실행이다\n"
            .format(worst.sessions))
        log("| 후보 | p50 | p95 | 최대 | 최대/p50 |")
        log("|---|---|---|---|---|")
        worst.stats.forEachIndexed { i, s ->
            log(
                "| %s | %.2f ms | **%.2f ms** | %.2f ms | %.1f배 |".format(
                    labels[i], s.p50, s.p95, s.max, if (s.p50 > 0) s.max / s.p50 else 0.0,
                ),
            )
        }
    }

    private fun log(message: String) {
        // logcat은 긴 줄을 자르므로 줄 단위로 나눠 찍는다.
        message.split('\n').forEach { android.util.Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "DbBench"

        /** 하루 한 세션 기준으로 10개 ≈ 열흘, 2000개 ≈ 5년 반. */
        val SIZES = listOf(10, 100, 500, 2000)
        const val REPS_PER_SESSION = 20

        /** 페이지 캐시와 준비된 구문이 찰 때까지 버리는 횟수. */
        const val WARMUP = 5

        /**
         * 표본 수. **p95를 내려면 9회로는 부족하다** — 9회의 p95는 사실상 최댓값이라
         * 분포를 말해주지 못한다. 50회면 상위 3개 안에서 p95가 정해져 "느린 쪽"의
         * 윤곽이 나온다.
         *
         * 더 늘리지 않는 이유: 표본을 키우면 측정에 걸리는 시간이 늘고, 그동안 기기
         * 온도와 백그라운드 작업이 변해 **다른 조건을 섞어 재게 된다.**
         */
        const val RUNS = 50

        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 고정 기준 시각. `System.currentTimeMillis()`를 쓰면 실행할 때마다 데이터가 달라진다. */
        const val BASE_TIME = 1_700_000_000_000L
    }
}
