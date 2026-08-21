package com.mist.formchecker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    version = 1,
)
@TypeConverters(Converters::class)
abstract class FormCheckerDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        const val NAME = "formchecker.db"
    }
}
