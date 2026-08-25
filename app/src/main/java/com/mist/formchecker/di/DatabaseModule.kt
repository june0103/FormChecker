package com.mist.formchecker.di

import android.content.Context
import androidx.room.Room
import com.mist.formchecker.data.local.FormCheckerDatabase
import com.mist.formchecker.data.local.WorkoutDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FormCheckerDatabase =
        Room.databaseBuilder(context, FormCheckerDatabase::class.java, FormCheckerDatabase.NAME)
            // 파괴적 마이그레이션을 쓰지 않는다 — 이 DB가 1차 저장소이고 아직 서버 동기화가
            // 없어서, 스키마가 바뀌면 사용자 기록이 그대로 사라진다.
            .addMigrations(
                FormCheckerDatabase.MIGRATION_1_2,
                FormCheckerDatabase.MIGRATION_2_3,
                FormCheckerDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    fun workoutDao(db: FormCheckerDatabase): WorkoutDao = db.workoutDao()
}
