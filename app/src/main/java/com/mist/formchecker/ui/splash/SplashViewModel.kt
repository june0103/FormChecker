package com.mist.formchecker.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.data.GuideKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 스플래시가 끝난 뒤 어디로 갈지.
 *
 * ## 왜 스플래시가 이걸 아는가
 * 온보딩 여부를 홈에서 판단하면 **홈이 한 프레임 그려진 뒤 온보딩이 덮는다.** 어차피
 * 모션이 도는 동안 디스크를 읽을 시간이 있으므로, 갈 곳이 정해지기 전에는 스플래시를
 * 끝내지 않는다([FormCheckerSplash]의 `ready`).
 *
 * `null`은 "아직 못 읽었다"다. 기본값을 정해두면 그 기본값이 한 번 실행된다.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    settings: AppSettings,
) : ViewModel() {

    val needsOnboarding: StateFlow<Boolean?> = settings.guideSeen(GuideKey.ONBOARDING)
        .map<Boolean, Boolean?> { seen -> !seen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
