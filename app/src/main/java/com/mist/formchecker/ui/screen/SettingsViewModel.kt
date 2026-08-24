package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.data.BodyInput
import com.mist.formchecker.data.BodyProfile
import com.mist.formchecker.di.ApplicationScope
import com.mist.formchecker.poseengine.Sex
import com.mist.formchecker.ui.screen.workout.CalibrationPrep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 설정 화면 상태.
 *
 * ## 읽기와 쓰기가 다른 스코프에서 돈다
 * 읽기([prepSeconds]·[bodyInput])는 `viewModelScope`다 — 화면이 사라지면 구독을 끊는 것이
 * 맞다. **쓰기는 [appScope]다** — 화면이 사라졌다고 취소되면 안 된다. 이 화면은 글자를 칠
 * 때마다 저장하므로, 마지막 글자를 치고 곧바로 나가면 `viewModelScope`에서는 그 값이
 * 사라진다([com.mist.formchecker.di.ApplicationScope] 참고).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    /**
     * 저장 전용 스코프. **`viewModelScope`를 쓰면 안 된다** — 마지막 입력이 화면을 나가는
     * 순간 취소된다.
     */
    @ApplicationScope private val appScope: CoroutineScope,
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

    /**
     * 저장된 신체 정보 **원값**. 반쯤 입력된 상태도 그대로 나온다.
     *
     * ## null은 "값이 없다"가 아니라 "아직 못 읽었다"다
     * 디스크를 읽기 전에는 null이고, 읽고 나면 [BodyInput]이 나온다(네 값이 모두 비어 있을
     * 수도 있다). 화면이 입력칸을 **한 번만** 채우려면 그 둘을 구분해야 한다 — 구분하지
     * 않으면 첫 프레임의 빈 값으로 칸을 채운 뒤 다시 채우지 않는다.
     *
     * 계산 가능한지는 `bodyInput.value?.profile`로 본다. 원값을 감추면 화면이 저장된 값을
     * 되보여 줄 수 없고, 되보여 주지 못한 값은 사용자의 다음 입력에 덮어써진다
     * ([AppSettings.bodyInput] 주석의 실측 사례).
     *
     * 입력 중인 글자는 여기 담지 않는다 — "17"까지 친 순간 키가 17cm로 저장되면 안 된다.
     * 화면이 자기 입력 상태를 들고 있고, 유효한 값이 됐을 때만 [saveHeight]를 부른다.
     */
    val bodyInput: StateFlow<BodyInput?> = settings.bodyInput
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * 키를 저장한다. 범위를 벗어난 값은 무시한다. null이면 지운다.
     *
     * 몸무게와 따로 저장한다 — 한 번에 둘을 쓰면 한쪽 칸이 비어 있을 때 다른 쪽이 함께
     * 지워진다([AppSettings.setHeightCm] 참고).
     */
    fun saveHeight(heightCm: Float?) {
        // 오타 방어. 250cm가 저장되면 열량이 부풀려지는데 사용자는 그게 틀렸다는 것을
        // 알 방법이 없다.
        if (heightCm != null && !BodyProfile.heightValid(heightCm)) return
        appScope.launch { settings.setHeightCm(heightCm) }
    }

    /** 몸무게를 저장한다. 범위를 벗어난 값은 무시한다. null이면 지운다. */
    fun saveWeight(weightKg: Float?) {
        // 700kg이 저장되면 열량이 10배로 나오는데 사용자는 그게 틀렸다는 것을 알 방법이 없다.
        if (weightKg != null && !BodyProfile.weightValid(weightKg)) return
        appScope.launch { settings.setWeightKg(weightKg) }
    }

    /** 나이. 범위를 벗어나면 무시한다. null이면 지운다. */
    fun saveAge(age: Int?) {
        if (age != null && !BodyProfile.ageValid(age)) return
        appScope.launch { settings.setAge(age) }
    }

    /** 성별. null이면 지운다. */
    fun saveSex(sex: Sex?) {
        appScope.launch { settings.setSex(sex) }
    }

    fun selectPrepSeconds(seconds: Int) {
        // 목록 밖의 값이 들어오면 저장하지 않는다. 화면이 목록만 보여주므로 지금은 일어날
        // 수 없지만, 저장된 값은 다음 실행에서도 쓰이므로 여기서 막는 편이 싸다.
        if (seconds !in CalibrationPrep.OPTIONS) return
        appScope.launch { settings.setCalibrationPrepSeconds(seconds) }
    }
}
