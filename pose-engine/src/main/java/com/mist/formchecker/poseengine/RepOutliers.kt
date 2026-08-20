package com.mist.formchecker.poseengine

import kotlin.math.abs

/**
 * 세션 안에서 다른 rep들과 크게 다른 rep을 찾는다.
 *
 * ## 왜 필요한가
 * 기준 표본을 만들 때 "이 rep이 정상이었나"를 사람에게 물으면 **알 수 없는 것을 묻는
 * 것**이다. 촬영자는 자기 화면을 보지 못했고, 봤어도 판단 기준(=지금 만들려는 것)이
 * 아직 없다. 그 추측이 `include_in_reference`가 되면 순환이 된다.
 *
 * 사람이 확실히 아는 것은 **의도**뿐이다("정상으로 찍으려 했다"). 그래서 의도는 세션 단위로
 * 받고, "그 의도대로 됐는가"는 **데이터 자신에게 묻는다** — 같은 의도로 찍은 rep들끼리
 * 비교해 유독 다른 것만 골라낸다.
 *
 * ## 왜 표준편차가 아니라 MAD인가
 * 표준편차는 평균과 제곱을 쓰므로 **이상치가 자기를 재는 자를 부풀려 자기를 숨긴다.**
 * 실측 21 rep에서 659ms짜리 rep의 편차가 표준편차 기준 4.0σ, MAD 기준 14.5였다. 그 rep을
 * 제외하고 표준편차를 다시 계산하면 8.8σ가 되는데, 그건 **이상치를 이미 알아야 이상치를
 * 찾을 수 있는 순환**이다. 중앙값과 MAD는 절반 이상이 오염되지 않는 한 흔들리지 않는다.
 *
 * 문서 §15.2도 같은 이유로 "평균과 표준편차만 사용하는 것보다 중앙값, 백분위수와 MAD를
 * 권장한다"고 적었다.
 */
object RepOutliers {

    /**
     * 이상치 판정 경계(MAD 단위).
     *
     * 실측 21 rep에서 3.0이면 4개(19%)가 걸렸다. 2.5는 6개, 4.0은 2개다. 확인 부담과
     * 오염 위험의 교환이며, 실기 데이터를 보고 조정할 값이다.
     */
    const val THRESHOLD = 3.0f

    /**
     * MAD가 의미를 갖는 최소 표본 수.
     *
     * 표본이 적으면 중앙값 자체가 불안정해 "다르다"는 판단이 무의미해진다. 미달이면
     * 편차를 내지 않고(null) 자동 제외도 하지 않는다 — 판단 근거가 없을 때 배제하는 것이
     * 더 나쁘다.
     */
    const val MIN_SAMPLES = 8

    /** 정규분포에서 MAD를 표준편차와 같은 스케일로 맞추는 상수. */
    private const val NORMAL_SCALE = 1.4826f

    /**
     * 비교에 쓰는 특징.
     *
     * 촬영 각도에 무관하게 채워지는 값만 쓴다 — 정면 전용 특징을 넣으면 측면 세션에서
     * 전부 null이 되어 비교가 안 된다. 반대로 각도별 특징까지 넣으면 세션마다 기준이
     * 달라져 "같은 3 MAD"가 다른 뜻이 된다.
     */
    private val FEATURES: List<Pair<String, (CaptureRep) -> Float?>> = listOf(
        "duration_ms" to { it.durationMs.toFloat() },
        "max_knee_flexion" to { it.maxKneeFlexion },
        "max_depth_ratio" to { it.maxDepthRatio },
        "min_thigh_angle" to { it.minThighAngle },
        "max_trunk_lean" to { it.maxTrunkLean },
        "frame_count" to { it.frameCount.toFloat() },
    )

    /**
     * rep별 최대 편차를 계산한다.
     *
     * @return rep id → 편차(MAD 단위). 표본이 [MIN_SAMPLES] 미만이면 빈 맵.
     */
    fun deviations(reps: List<CaptureRep>): Map<Int, Float> {
        // 카운트된 rep만 기준으로 삼는다. 중단된 rep은 애초에 동작이 다르므로 중앙값을
        // 오염시킨다.
        val basis = reps.filter { it.counted }
        if (basis.size < MIN_SAMPLES) return emptyMap()

        val stats = FEATURES.mapNotNull { (name, extract) ->
            val values = basis.mapNotNull(extract)
            if (values.size < MIN_SAMPLES) return@mapNotNull null
            val center = median(values)
            val scale = NORMAL_SCALE * median(values.map { abs(it - center) })
            // MAD가 0이면 절반 이상이 같은 값이다. 그 특징으로는 편차를 낼 수 없다
            // (어떤 차이든 무한이 된다).
            if (scale < 1e-6f) null else Triple(name, center, scale)
        }
        if (stats.isEmpty()) return emptyMap()

        return reps.mapNotNull { rep ->
            val worst = FEATURES.mapNotNull { (name, extract) ->
                val stat = stats.firstOrNull { it.first == name } ?: return@mapNotNull null
                extract(rep)?.let { abs(it - stat.second) / stat.third }
            }.maxOrNull()
            worst?.let { rep.repId to it }
        }.toMap()
    }

    fun isOutlier(deviation: Float?): Boolean =
        deviation != null && deviation > THRESHOLD

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}
