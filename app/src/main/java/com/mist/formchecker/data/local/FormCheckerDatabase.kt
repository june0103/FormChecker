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
    version = 2,
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
    }
}
