package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.ui.screen.workout.CalibrationPrep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
) : ViewModel() {

    /**
     * 준비 시간(초).
     *
     * 저장된 값이 없으면 기본값을 낸다 — 기본값은 이 값을 쓰는 쪽(`CalibrationPrep`)이
     * 알고 있고, 저장소는 "없음"만 돌려준다([AppSettings] 참고).
     *
     * `stateIn`의 초기값도 기본값이다. 첫 프레임에 잠깐 다른 값이 보이면 사용자는 설정이
     * 되돌아간 것으로 읽는다 — 디스크 읽기가 끝나기 전에 0을 보여주면 "바로"가 선택된
     * 것처럼 보인다.
     */
    val prepSeconds: StateFlow<Int> = settings.calibrationPrepSeconds
        .map { it ?: CalibrationPrep.DEFAULT_SECONDS }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CalibrationPrep.DEFAULT_SECONDS,
        )

    fun selectPrepSeconds(seconds: Int) {
        // 목록 밖의 값이 들어오면 저장하지 않는다. 화면이 목록만 보여주므로 지금은 일어날
        // 수 없지만, 저장된 값은 다음 실행에서도 쓰이므로 여기서 막는 편이 싸다.
        if (seconds !in CalibrationPrep.OPTIONS) return
        viewModelScope.launch { settings.setCalibrationPrepSeconds(seconds) }
    }
}
