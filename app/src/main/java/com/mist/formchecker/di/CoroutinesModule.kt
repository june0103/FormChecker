package com.mist.formchecker.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 화면 생명주기와 무관하게 끝까지 돌아야 하는 작업의 스코프.
 *
 * `viewModelScope`와 구분하기 위한 표시다. ViewModel 스코프는 화면이 사라지면 취소되는데,
 * **디스크에 값을 쓰는 일은 화면이 사라진다고 취소돼도 되는 일이 아니다.**
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    /**
     * 앱이 살아 있는 동안 유지되는 스코프.
     *
     * ## 왜 필요했나 — 마지막 입력이 사라질 수 있었다
     * 설정 화면은 글자를 칠 때마다 저장한다(나올 때 한 번 저장하는 구조가 아니다). 그
     * 쓰기를 `viewModelScope`에서 돌리면, 마지막 글자를 치고 **곧바로 뒤로 나갈 때**
     * ViewModel이 정리되면서 코루틴이 취소되고 그 값이 디스크에 안 남는다.
     *
     * 창이 좁아서 실기기에서 재현하지는 못했다(`adb`는 명령 사이에 100ms 이상이 끼어
     * 그 창을 못 맞춘다). 코드상 열려 있는 경로이고, 사용자 데이터가 조용히 사라지는
     * 종류라서 재현을 기다리지 않고 막는다.
     *
     * ## 나올 때 저장하는 방식으로 바꾸지 않은 이유
     * 그쪽이 더 쉬워 보이지만, 이미 저장된 값을 또 쓰게 되고 "언제 저장되나"가 두 곳으로
     * 갈라진다. 쓰기를 취소되지 않는 스코프로 옮기는 것이 원인을 고치는 쪽이다.
     *
     * ## SupervisorJob
     * 한 저장이 실패해도 스코프가 죽지 않아야 한다. 일반 [kotlinx.coroutines.Job]이면
     * 자식 하나의 예외가 스코프를 취소시켜 **그 뒤의 모든 설정 저장이 조용히 무시된다.**
     *
     * 여기서 [kotlinx.coroutines.CoroutineScope.cancel]을 부르는 곳은 없다 — 앱 프로세스와
     * 수명이 같다. `@Singleton`이 그걸 보장한다.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
