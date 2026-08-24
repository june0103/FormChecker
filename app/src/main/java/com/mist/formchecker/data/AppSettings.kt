package com.mist.formchecker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자가 고르는 설정. 앱을 닫아도 남는다.
 *
 * ## 왜 Room이 아닌가
 * Room은 **기록**의 1차 저장소다(세션·rep·성능). 여기 있는 것은 스칼라 몇 개라 테이블도
 * 마이그레이션도 만들 이유가 없고, 무엇보다 **동기화 대상이 아니다** — `sync_status`가
 * 붙어야 할 값이 하나도 없다. 같은 저장소에 두면 "이 행은 서버로 보내지 않는다"는 예외를
 * 스키마에 만들어야 한다.
 *
 * ## 왜 SharedPreferences가 아닌가
 * 읽기가 [Flow]여야 한다. 설정을 바꾸면 운동 화면이 **다시 들어가지 않고** 반영돼야 하고,
 * SharedPreferences로 그걸 하려면 리스너를 손으로 이어야 한다. 그리고 그쪽은 첫 읽기가
 * 메인 스레드 디스크 I/O다.
 *
 * ## 기본값을 여기서 정하지 않는다
 * 각 설정의 기본값은 그 값을 쓰는 쪽이 안다(`CalibrationPrep.DEFAULT_SECONDS`). 저장소가
 * 기본값을 또 들고 있으면 두 곳이 갈라진다.
 */
@Singleton
class AppSettings @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    /**
     * 기준 자세 측정을 시작하기 전에 줄 준비 시간(초).
     *
     * 예전에는 운동 화면에 선택 줄이 있었다. 화면에 둔 이유는 "삼각대 위치나 방 크기에
     * 따라 크게 달라진다"였는데, 매번 같은 자리에서 찍는 사람에게는 그 줄이 화면만
     * 차지했다. 설정으로 옮기고 값을 기억한다.
     *
     * @return 저장된 값이 없으면 null. 호출부가 자기 기본값을 쓴다.
     */
    val calibrationPrepSeconds: Flow<Int?> =
        store.data.map { it[KEY_PREP_SECONDS] }

    suspend fun setCalibrationPrepSeconds(seconds: Int) {
        store.edit { it[KEY_PREP_SECONDS] = seconds }
    }

    private companion object {
        val KEY_PREP_SECONDS = intPreferencesKey("calibration_prep_seconds")
    }
}
