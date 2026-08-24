package com.mist.formchecker.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    /**
     * 설정 저장소.
     *
     * ## `@Singleton`이어야 한다
     * DataStore는 파일 하나를 잡는다. 인스턴스를 두 개 만들면 같은 파일을 두 곳에서 열어
     * **런타임에 터진다**(`IllegalStateException: There are multiple DataStores active`).
     * 이 어노테이션이 그걸 막는 유일한 장치다.
     *
     * 확장 프로퍼티(`Context.dataStore`) 방식을 쓰지 않은 이유도 같다 — 그 방식은
     * 싱글턴 보장을 파일 최상단 위임에 맡기므로 Hilt 그래프 밖에 있고, 테스트에서
     * 갈아끼울 수 없다.
     */
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(NAME) },
        )

    private const val NAME = "settings"
}
