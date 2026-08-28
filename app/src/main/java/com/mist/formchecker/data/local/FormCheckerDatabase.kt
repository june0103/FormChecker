package com.mist.formchecker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 로컬 1차 저장소. 설계문서 4장 스키마 + `sync_status`.
 *
 * **오프라인 우선 원칙(설계문서 195행)의 구현 지점이다.** 운동 결과는 항상 여기 먼저
 * 들어가고, 서버 전송은 나중에 별도로 일어난다. 네트워크 실패가 운동 흐름을 막지 않는다는
 * 것은 이 순서에서 나온다.
 *
 * `exportSchema = true`(기본값)로 두고 `app/schemas`에 스키마를 남긴다 — 마이그레이션을
 * 손으로 쓸 때 이전 스키마가 없으면 무엇이 바뀌었는지 알 수 없다.
 */
@Database(
    entities = [
        WorkoutSessionEntity::class,
        WorkoutSetEntity::class,
        RepRecordEntity::class,
        PerformanceMetricsEntity::class,
    ],
    version = 5,
)
@TypeConverters(Converters::class)
abstract class FormCheckerDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        const val NAME = "formchecker.db"

        /**
         * 무릎 편차가 컸던 쪽을 rep에 남긴다. 세션 리포트가 "왼쪽 무릎을"이라고 쪽을
         * 지목하는 데 쓴다.
         *
         * ## 왜 v1을 고치지 않고 마이그레이션을 쓰는가
         * v1이 릴리스된 적은 없지만 디버그 빌드로 기기에 이미 깔려 있을 수 있다. 그 경우
         * 스키마만 바꾸면 Room이 실행 시점에 예외를 던진다. 파괴적 마이그레이션으로
         * 덮으면 예외는 사라지지만 **기록이 조용히 사라진다** — 이 DB가 1차 저장소이고
         * 서버 사본이 아직 없다.
         *
         * 기존 행의 값은 null이다. 그때는 리포트가 쪽을 붙이지 않고 넘어간다.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rep_records ADD COLUMN knee_toe_side TEXT")
            }
        }

        /**
         * 경고가 rep의 어느 구간에서 났는지 남긴다.
         *
         * 기존 행은 null이고, 리포트는 그때 구간을 말하지 않는다 — 없는 정보를 지어내는
         * 것보다 침묵이 낫다.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rep_records ADD COLUMN warning_phases TEXT")
            }
        }

        /**
         * **어느 기준으로 판정했는지**를 남긴다.
         *
         * 이 컬럼들이 없는 동안 쌓인 기록은 기준 완화가 켜졌는지 꺼졌는지 알 수 없어,
         * 나중에 임계값을 다시 계산할 때 섞인 데이터가 된다. 기존 행은 전부 null —
         * **false로 채우지 않는다.** 모르는 것을 "꺼짐"으로 세면 그만큼 통계가 틀어지고,
         * 그 사실이 데이터에서 사라진다.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rep_records ADD COLUMN relaxed_form INTEGER")
                db.execSQL("ALTER TABLE rep_records ADD COLUMN knee_tolerance REAL")
                db.execSQL("ALTER TABLE rep_records ADD COLUMN shallow_tolerance REAL")
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN form_thresholds TEXT")
            }
        }

        /**
         * `workout_sessions.started_at`에 인덱스를 만든다. **데이터는 건드리지 않는다.**
         *
         * ## 왜 필요한가
         * 홈 화면이 앱을 켤 때마다 `started_at` 범위로 이번 주를 자르는데
         * ([WorkoutDao.sessionRepCountsBetween]), 인덱스가 없어 **세션 테이블을 전부
         * 훑고 있었다.** 기록 목록의 `ORDER BY started_at DESC`도 정렬을 위해 매번
         * 전체를 읽었다.
         *
         * 개발 중에는 세션이 열 개 남짓이라 드러나지 않는다. **기록이 쌓인 사용자에게만
         * 느려지므로**, 재는 장치(`DbQueryBenchmark`) 없이는 발견되지 않는 종류다.
         *
         * ## 이름을 Room 규칙에 맞춘 이유
         * `index_<테이블>_<컬럼>`은 Room이 엔티티에서 생성하는 이름이다. 다른 이름으로
         * 만들면 Room이 시작할 때 스키마를 대조하면서 **인덱스가 없다고 판단해 예외를
         * 던진다** — 인덱스가 실제로는 있어도 그렇다.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_sessions_started_at` " +
                        "ON `workout_sessions` (`started_at`)",
                )
            }
        }
    }
}
