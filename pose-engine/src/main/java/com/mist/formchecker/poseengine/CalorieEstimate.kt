package com.mist.formchecker.poseengine

/**
 * 스쿼트 소모 열량 추정. **횟수 기반**이다.
 *
 * ## 근거
 * | 값 | 출처 |
 * |---|---|
 * | 스쿼트 5.0 METs | 2011 Compendium of Physical Activities, 코드 `02052` — "resistance (weight) training, squats, slow or explosive effort" (Ainsworth et al., *Med Sci Sports Exerc* 43:1575, 2011) |
 * | 기준 대사량(RMR) | Mifflin-St Jeor 식 (1990). 성인 498명 간접열량계 측정에서 도출됐고, 2005년 체계적 문헌고찰에서 Harris-Benedict·Owen·WHO 식보다 오차가 작았다 |
 * | 분당 10회 | Nakagata et al., *J Strength Cond Res* (2022)가 외삽 비교에 쓴 빈도 |
 *
 * ## 왜 시간이 아니라 횟수인가
 * Nakagata et al.는 **에너지 소비와 반복 빈도의 회귀 기울기**를 1회당 열량으로 정의했다.
 * 기울기는 빈도와 무관한 값이므로 **1회당 열량은 얼마나 천천히 했는지에 좌우되지 않는다** —
 * 스쿼트 한 번의 대사 비용은 기계적 일과 고정 부하의 합이지 걸린 시간이 아니다.
 * 그래서 이 앱이 재는 것(횟수)이 곧 계산의 입력이다.
 *
 * ## 계산
 * ```
 * RMR(kcal/일)  = 10×kg + 6.25×cm − 5×나이 + (남 +5 / 여 −161)      [Mifflin-St Jeor]
 * 1회당(kcal)   = 5.0 METs × RMR/1440 ÷ 10회             [Compendium × 분당 10회]
 * 총 열량       = 1회당 × 기록된 횟수
 * ```
 *
 * ## 두 문헌이 서로를 검증한다
 * 위 식에 70kg · 170cm · 25세 · 남성을 넣으면 **1회당 0.57 kcal**이 나온다.
 * Nakagata et al.의 실측은 **0.50 ± 0.14 kcal**(95% CI 0.42~0.58)이므로 **신뢰구간
 * 안이다.** MET 값과 실측 회귀가 독립적으로 같은 자리를 가리킨다.
 *
 * ## 그래도 "약"을 붙인다
 * 실측 표본이 **21~29세 남성 15명**이고, 무산소 대사와 운동 후 초과산소섭취(EPOC)는 포함되지
 * 않는다. 나이·성별을 넣지 않으면 [ASSUMED_AGE]·[ASSUMED_SEX]를 쓰므로 그만큼 더 벌어진다.
 */
object CalorieEstimate {

    /**
     * 스쿼트 MET.
     *
     * 2011 Compendium 코드 `02052`: "resistance (weight) training, squats, slow or explosive
     * effort" = **5.0**. 공식 부록 PDF에서 직접 확인했다.
     *
     * 같은 문서의 코드 `02050`(웨이트 트레이닝 전반, vigorous)은 6.0인데, 그건 세트 사이
     * 휴식까지 포함한 세션 전체의 값이라 1회당 환산에 쓰면 과대추정이 된다.
     */
    const val SQUAT_MET = 5.0f

    /**
     * 1회당으로 환산할 때 쓰는 빈도(회/분).
     *
     * Nakagata et al.(2022)이 외삽 비교에 쓴 빈도이고, MET 값이 전제하는 "slow or explosive
     * effort"의 실제 템포에 해당한다.
     *
     * ## 사용자가 빠르게 해도 이 값을 쓴다
     * 실측 rep은 중앙값 1.7초(≈35회/분)지만, **1회당 열량은 템포에 좌우되지 않는다**
     * (위 회귀 기울기 논거). 측정한 rep 시간으로 나누면 빠르게 한 사람이 열량을 3배 적게
     * 받는데, 그건 문헌이 말하는 바와 반대다.
     */
    const val REPS_PER_MINUTE = 10f

    /** 나이를 넣지 않았을 때 쓰는 값. **화면에 밝힌다.** */
    const val ASSUMED_AGE = 30

    /** 성별을 넣지 않았을 때 쓰는 값. **화면에 밝힌다.** */
    val ASSUMED_SEX = Sex.MALE

    private const val MINUTES_PER_DAY = 1440f

    /**
     * Mifflin-St Jeor 기초대사량(kcal/일).
     *
     * ```
     * 남: 10×kg + 6.25×cm − 5×나이 + 5
     * 여: 10×kg + 6.25×cm − 5×나이 − 161
     * ```
     *
     * **키가 여기서 쓰인다.** MET 표준식(`MET × 3.5 × kg / 200`)은 체중만 보는 근사인데,
     * 그 3.5 mL/kg/min이 곧 "체중으로만 정규화한 RMR"이다. 실측 회귀식으로 바꾸면 키가
     * 들어오고 오차가 줄어든다.
     */
    fun restingKcalPerDay(heightCm: Float, weightKg: Float, age: Int, sex: Sex): Float {
        val base = 10f * weightKg + 6.25f * heightCm - 5f * age
        return base + if (sex == Sex.MALE) 5f else -161f
    }

    /**
     * 스쿼트 1회당 소모 열량(kcal).
     *
     * @param age null이면 [ASSUMED_AGE]를 쓴다. 화면이 그 사실을 알려야 한다.
     * @param sex null이면 [ASSUMED_SEX]를 쓴다. 화면이 그 사실을 알려야 한다.
     * @return 키·몸무게가 유효하지 않으면 null. **0이 아니다** — 0은 "안 태웠다"이고
     *   여기서 말하려는 것은 "모른다"다.
     */
    fun perRep(
        heightCm: Float,
        weightKg: Float,
        age: Int? = null,
        sex: Sex? = null,
    ): Float? {
        if (heightCm <= 0f || weightKg <= 0f) return null
        val rmr = restingKcalPerDay(
            heightCm = heightCm,
            weightKg = weightKg,
            age = age ?: ASSUMED_AGE,
            sex = sex ?: ASSUMED_SEX,
        )
        if (rmr <= 0f) return null
        return SQUAT_MET * (rmr / MINUTES_PER_DAY) / REPS_PER_MINUTE
    }

    /**
     * 기록된 횟수로 세션 열량을 낸다.
     *
     * @param reps 기록된 횟수. **깊이를 잰 rep만 세지 않는다** — 1회당 열량이 깊이가 아니라
     *   반복 자체에서 나오므로, 정면으로 찍은 세션도 계산된다.
     */
    fun forSession(
        reps: Int,
        heightCm: Float,
        weightKg: Float,
        age: Int? = null,
        sex: Sex? = null,
    ): SessionCalories? {
        if (reps <= 0) return null
        val perRep = perRep(heightCm, weightKg, age, sex) ?: return null
        return SessionCalories(
            kcal = perRep * reps,
            perRepKcal = perRep,
            reps = reps,
            assumedAge = age == null,
            assumedSex = sex == null,
        )
    }
}

/** 생물학적 성별. Mifflin-St Jeor 식의 상수항이 여기서 갈린다. */
enum class Sex { MALE, FEMALE }

/**
 * 세션 열량 요약.
 *
 * @property assumedAge 나이를 넣지 않아 기본값을 썼나. **화면이 이걸 밝혀야 한다** —
 *   추정에 들어간 가정을 숨기면 사용자가 값을 실측으로 읽는다.
 * @property assumedSex 성별을 넣지 않아 기본값을 썼나.
 */
data class SessionCalories(
    val kcal: Float,
    val perRepKcal: Float,
    val reps: Int,
    val assumedAge: Boolean,
    val assumedSex: Boolean,
) {
    /** 가정 없이 넣은 값만으로 계산했나. */
    val exact: Boolean get() = !assumedAge && !assumedSex
}
