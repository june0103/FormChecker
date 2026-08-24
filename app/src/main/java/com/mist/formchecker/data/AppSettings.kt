package com.mist.formchecker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mist.formchecker.poseengine.Sex
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

    /**
     * 키와 몸무게. **둘 다 있을 때만** 값이 나온다.
     *
     * ## 왜 하나만 있으면 null인가
     * 열량 추정이 쓰는 Mifflin-St Jeor 기초대사량 식이 **키와 몸무게를 둘 다** 요구한다
     * (`10×kg + 6.25×cm − 5×나이 + 상수`, [CalorieEstimate] 참고). 하나만 있으면 계산이
     * 성립하지 않으므로 "반쯤 입력된 상태"를 소비자가 신경 쓰지 않게 여기서 막는다.
     *
     * 입력하지 않는 것이 정상 상태다 — 칼로리는 부가 기능이고, 없으면 그 항목만 빠진다.
     */
    val bodyProfile: Flow<BodyProfile?> = store.data.map { prefs ->
        val height = prefs[KEY_HEIGHT_CM]
        val weight = prefs[KEY_WEIGHT_KG]
        if (height == null || weight == null) {
            null
        } else {
            BodyProfile(
                heightCm = height,
                weightKg = weight,
                // 나이·성별은 선택이다. 없으면 계산이 기본값을 쓰고 그 사실을 화면에 밝힌다
                // (Mifflin-St Jeor 식이 둘을 요구한다).
                age = prefs[KEY_AGE],
                sex = prefs[KEY_SEX]?.let { name ->
                    runCatching { Sex.valueOf(name) }.getOrNull()
                },
            )
        }
    }

    /**
     * 키·몸무게를 저장한다. null을 주면 그 값을 지운다.
     *
     * 지우는 길을 남기는 이유: 한 번 넣으면 못 지우는 신체 정보는 사용자가 되돌릴 수 없다.
     */
    suspend fun setBodyProfile(heightCm: Float?, weightKg: Float?) {
        store.edit { prefs ->
            if (heightCm == null) prefs.remove(KEY_HEIGHT_CM) else prefs[KEY_HEIGHT_CM] = heightCm
            if (weightKg == null) prefs.remove(KEY_WEIGHT_KG) else prefs[KEY_WEIGHT_KG] = weightKg
        }
    }

    /** 나이. null이면 지운다. 넣으면 열량 추정이 정확해진다. */
    suspend fun setAge(age: Int?) {
        store.edit { prefs -> if (age == null) prefs.remove(KEY_AGE) else prefs[KEY_AGE] = age }
    }

    /** 성별. null이면 지운다. Mifflin-St Jeor 식의 상수항이 여기서 갈린다. */
    suspend fun setSex(sex: Sex?) {
        store.edit { prefs ->
            if (sex == null) prefs.remove(KEY_SEX) else prefs[KEY_SEX] = sex.name
        }
    }

    private companion object {
        val KEY_PREP_SECONDS = intPreferencesKey("calibration_prep_seconds")
        val KEY_HEIGHT_CM = floatPreferencesKey("body_height_cm")
        val KEY_WEIGHT_KG = floatPreferencesKey("body_weight_kg")
        val KEY_AGE = intPreferencesKey("body_age")
        val KEY_SEX = stringPreferencesKey("body_sex")
    }
}

/**
 * 칼로리 추정에 쓰는 신체 정보.
 *
 * ## 범위를 두는 이유
 * 의학적 판단이 아니라 **오타 방어**다. 몸무게에 700을 넣으면 칼로리가 10배로 나오는데
 * 그게 틀렸다는 것을 사용자가 알 방법이 없다. 사람이 실제로 가질 수 있는 범위를 넘으면
 * 저장하지 않는다.
 */
data class BodyProfile(
    val heightCm: Float,
    val weightKg: Float,
    /** 선택. 없으면 열량 추정이 기본값을 쓰고 그 사실을 화면에 밝힌다. */
    val age: Int? = null,
    /** 선택. 없으면 위와 같다. */
    val sex: Sex? = null,
) {
    companion object {
        val HEIGHT_RANGE_CM = 100f..250f
        val WEIGHT_RANGE_KG = 20f..300f

        /** 나이 상한을 두는 이유도 오타 방어다 — Mifflin-St Jeor 식은 19~78세에서 도출됐다. */
        val AGE_RANGE = 10..100

        fun heightValid(cm: Float) = cm in HEIGHT_RANGE_CM
        fun weightValid(kg: Float) = kg in WEIGHT_RANGE_KG
        fun ageValid(age: Int) = age in AGE_RANGE
    }
}
