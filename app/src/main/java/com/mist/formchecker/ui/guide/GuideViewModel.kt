package com.mist.formchecker.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.data.GuideKey
import com.mist.formchecker.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 사용자 안내를 보여줄지 정하는 자리. **화면은 DataStore를 직접 읽지 않는다.**
 *
 * ## `null`이 "아직 모른다"다
 * 디스크를 읽기 전에는 `null`이고, 읽고 나서야 `true`/`false`가 된다. 이 구분이 없으면
 * 첫 프레임에 "안 봤다"로 판단해 **이미 본 안내가 한 번 깜빡인다.** 화면은 `null`인 동안
 * 아무 안내도 띄우지 않는다.
 *
 * ## 저장은 앱 스코프에서
 * 안내를 닫는 동작은 대개 화면 이동과 함께 일어난다. `viewModelScope`에 저장을 걸면
 * 그 화면이 사라지면서 취소되어 **다음에 또 뜬다**([SettingsViewModel]과 같은 이유).
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val settings: AppSettings,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val cache = mutableMapOf<GuideKey, StateFlow<Boolean?>>()

    /** 이 안내를 이미 봤나. `null`이면 아직 못 읽었다. */
    fun seen(key: GuideKey): StateFlow<Boolean?> = cache.getOrPut(key) {
        settings.guideSeen(key)
            .map<Boolean, Boolean?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** 끝까지 봤거나 건너뛰었다. 도중에 나간 경우에는 부르지 않는다. */
    fun markSeen(key: GuideKey) {
        appScope.launch { settings.markGuideSeen(key) }
    }

    /** 안내 하나를 "안 본 것"으로 되돌린다. 다음에 그 안내가 뜨는 자리에서 다시 보인다. */
    fun resetGuide(key: GuideKey) {
        appScope.launch { settings.resetGuide(key) }
    }

    /** 화면별 코치마크만 초기화한다. 온보딩·측정 준비·동작 예시는 그대로 둔다. */
    fun resetMenuTutorials() {
        appScope.launch { settings.resetMenuTutorials() }
    }
}
