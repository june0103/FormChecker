package com.mist.formchecker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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
     * 자세 판정 기준을 완화할까. 지금은 **무릎–발끝 허용폭**에만 적용된다.
     *
     * ## 왜 이 설정이 생겼나
     * 실제 사용자가 *"맞게 하고 있는 것 같은데 계속 주의를 주니까 거부감이 든다"*고 했다.
     * 실측 12세션 240 rep을 다시 계산하니 **정상 의도 세션에서도 기본값(0.10)이 62%에
     * 경고를 냈다.** `FormThresholds.RELAXED_KNEE_TOE_TOLERANCE`(0.15)는 그것을 45%로
     * 내리면서 모임 검출을 15%까지 유지한다 — 그 값의 근거와 한계는 그쪽 주석에 있다.
     *
     * ## 여기만 `Boolean?`이 아니다
     * 다른 설정은 "저장 안 함"을 null로 내고 호출부가 기본값을 쓴다([calibrationPrepSeconds]).
     * **여기는 그럴 이유가 없다** — 준비 시간은 0초("바로")가 유효한 선택지라서 "저장 안
     * 함"과 구분해야 했지만, 토글에는 제3의 상태가 없다. 저장 전과 꺼짐은 동작이 같다.
     *
     * 기본값은 **꺼짐**이다. 확정된 임계값이 기본이어야 하고, 완화는 사용자가 고르는 것이다.
     */
    val relaxedForm: Flow<Boolean> =
        store.data.map { it[KEY_RELAXED_FORM] ?: false }

    suspend fun setRelaxedForm(enabled: Boolean) {
        store.edit { it[KEY_RELAXED_FORM] = enabled }
    }

    /**
     * 저장된 신체 정보 **원값**. 없는 값은 null이고, 반쯤 입력된 상태도 그대로 나온다.
     *
     * ## 왜 원값이 필요한가 — 감췄더니 데이터가 지워졌다
     * 처음에는 [bodyProfile]만 내보내면서 "반쯤 입력된 상태를 소비자가 신경 쓰지 않게 여기서
     * 막는다"고 적어 뒀다. 그런데 **설정 화면이 바로 그 원값이 필요한 소비자였다.** 키만
     * 저장된 상태에서 화면이 받는 값은 null이라 입력칸이 비어 보였고, 거기에 몸무게를 넣는
     * 순간 빈 키 칸이 함께 저장돼 **디스크에 있던 키가 지워졌다.**
     *
     * 실기기에서 재현했다: `body_height_cm`=175만 있는 상태에서 몸무게 70을 입력하니 그 키가
     * 파일에서 사라졌다. 그래서 **게이트(계산할 수 있나)와 원값(무엇이 저장돼 있나)을
     * 분리한다** — 감춘 상태는 화면이 복원할 수 없고, 복원할 수 없는 상태는 덮어써진다.
     */
    val bodyInput: Flow<BodyInput> = store.data.map { prefs ->
        BodyInput(
            heightCm = prefs[KEY_HEIGHT_CM],
            weightKg = prefs[KEY_WEIGHT_KG],
            // 나이·성별은 선택이다. 없으면 계산이 기본값을 쓰고 그 사실을 화면에 밝힌다
            // (Mifflin-St Jeor 식이 둘을 요구한다).
            age = prefs[KEY_AGE],
            sex = prefs[KEY_SEX]?.let { name ->
                runCatching { Sex.valueOf(name) }.getOrNull()
            },
        )
    }

    /**
     * 키와 몸무게. **둘 다 있을 때만** 값이 나온다 — "열량을 계산할 수 있는가"의 게이트다.
     *
     * 열량 추정이 쓰는 Mifflin-St Jeor 기초대사량 식이 키와 몸무게를 둘 다 요구한다
     * (`10×kg + 6.25×cm − 5×나이 + 상수`, [CalorieEstimate] 참고).
     *
     * 결과 화면처럼 **계산만 하는 소비자**가 이걸 쓴다. 저장된 값을 되보여 줘야 하는 쪽은
     * [bodyInput]을 써야 한다.
     *
     * 입력하지 않는 것이 정상 상태다 — 열량은 부가 기능이고, 없으면 그 항목만 빠진다.
     */
    val bodyProfile: Flow<BodyProfile?> = bodyInput.map { it.profile }

    /**
     * 키를 저장한다. null이면 지운다.
     *
     * **몸무게를 건드리지 않는다.** 예전에는 둘을 한 번에 받았는데, 한쪽 칸이 비어 있을 때
     * 다른 쪽 값이 함께 지워졌다([bodyInput] 주석의 실측 사례). 값마다 자기 것만 쓴다.
     *
     * 지우는 길을 남기는 이유: 한 번 넣으면 못 지우는 신체 정보는 사용자가 되돌릴 수 없다.
     */
    suspend fun setHeightCm(heightCm: Float?) {
        store.edit { prefs ->
            if (heightCm == null) prefs.remove(KEY_HEIGHT_CM) else prefs[KEY_HEIGHT_CM] = heightCm
        }
    }

    /** 몸무게를 저장한다. null이면 지운다. 키를 건드리지 않는다([setHeightCm] 참고). */
    suspend fun setWeightKg(weightKg: Float?) {
        store.edit { prefs ->
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
        val KEY_RELAXED_FORM = booleanPreferencesKey("relaxed_form")
    }
}

/**
 * 저장된 신체 정보 원값. **네 값이 각각 독립적으로 없을 수 있다.**
 *
 * [BodyProfile]과 나눈 이유: 이쪽은 "무엇이 저장돼 있나"이고 저쪽은 "계산할 수 있나"다.
 * 화면이 입력칸을 되채우려면 반쯤 입력된 상태를 그대로 볼 수 있어야 한다
 * ([AppSettings.bodyInput] 주석의 데이터 손실 사례).
 */
data class BodyInput(
    val heightCm: Float? = null,
    val weightKg: Float? = null,
    val age: Int? = null,
    val sex: Sex? = null,
) {
    /** 열량을 계산할 수 있으면 프로필, 아니면 null. 키·몸무게가 둘 다 있어야 한다. */
    val profile: BodyProfile?
        get() = if (heightCm == null || weightKg == null) {
            null
        } else {
            BodyProfile(heightCm = heightCm, weightKg = weightKg, age = age, sex = sex)
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
