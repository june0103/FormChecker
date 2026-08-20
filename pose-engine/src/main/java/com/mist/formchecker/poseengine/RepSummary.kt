package com.mist.formchecker.poseengine

/**
 * 한 rep의 요약.
 *
 * ## 왜 세 부분으로 나누는가
 * 구간 전체에서 뽑은 최솟값·최댓값을 한 덩어리에 섞으면 **실제로 존재하지 않은 자세를
 * 표현하게 된다.** 최저 무릎각이 나온 프레임과 최저 고관절각이 나온 프레임이 다를 수
 * 있기 때문이다. 그래서 "구간 집계"와 "특정 시점의 동기화된 값"을 타입으로 분리한다.
 *
 * ```
 * aggregate : 구간 전체에서 뽑은 값 (서로 다른 프레임에서 나올 수 있음)
 * top       : 선 자세 시점의 값 (모두 같은 프레임/창)
 * bottom    : 최저점 시점의 값 (모두 같은 프레임/창)
 * ```
 *
 * ## top이 필요한 이유
 * 데이터 측 분석에서 발뒤꿈치 들림 판정의 최우수 특징이 **선 자세 대비 상대값**(`delta_top`)
 * 계열이었다. 절대값 특징은 좌우 시점에 따라 최적 임계값이 0.037 차이 났는데, 상대값은
 * 0.009로 훨씬 작았다 — 개인 발 형태·카메라 오프셋이 상쇄되기 때문이다. 따라서 최저점만
 * 저장하면 그 특징을 계산할 수 없다.
 *
 * ## 왜 특징 수식이 아니라 좌표를 담는가
 * 최종 특징 수식과 임계값은 데이터·모델 담당 영역이다. 앱은 **두 시점의 좌표와 confidence를
 * 정확히 수집하는 것**까지 책임지고, 수식이 확정되면 계산만 얹는다. 그래서 이 타입은
 * 판정 결과가 아니라 원재료를 담는다.
 */
data class RepSummary(
    val repIndex: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val aggregate: RepAggregate,
    val top: RepPosture?,
    val bottom: RepPosture?,
    val cameraAngle: CameraAngle,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
}

/**
 * 구간 전체 집계.
 *
 * 각 필드가 서로 다른 프레임에서 나올 수 있다. 같은 시점의 조합이 필요하면
 * [RepSummary.bottom]을 써야 한다.
 */
data class RepAggregate(
    val minKneeAngle: Float?,
    val maxKneeAngle: Float?,
    val minHipAngle: Float?,
    val maxHipAngle: Float?,
    val maxTorsoLeanDegrees: Float?,
    /** 하강 구간 길이(ms). 시작 → 최저점. */
    val descentDurationMs: Long?,
    /** 상승 구간 길이(ms). 최저점 → 종료. */
    val ascentDurationMs: Long?,
    /**
     * 무릎 각속도 최댓값(**deg/s**).
     *
     * 프레임당 변화량이 아니라 초당 값이다. 분석 fps가 변동하므로 프레임 단위로 두면
     * 기기·조건에 따라 값이 달라져 비교가 불가능해진다.
     */
    val maxAngularVelocityDegPerSec: Float?,
    /** 각도를 얻은 프레임 비율. 낮으면 이 rep의 신뢰도가 낮다. */
    val validFrameRatio: Float,
    val analyzedFrameCount: Int,
) {
    val kneeRangeOfMotion: Float?
        get() = if (minKneeAngle != null && maxKneeAngle != null) {
            maxKneeAngle - minKneeAngle
        } else {
            null
        }
}

/**
 * 특정 시점(top 또는 bottom)의 동기화된 자세.
 *
 * 모든 값이 **같은 프레임 또는 같은 창**에서 나온다. 창을 쓰는 경우 median으로 축약한다 —
 * 단일 프레임 노이즈로 최저점이 흔들리지 않게 하기 위함이며, 데이터 측이 최저점 ±2 프레임
 * 중앙값을 쓴 것과 같은 방식이다.
 */
data class RepPosture(
    /** 이 자세를 대표하는 프레임 인덱스(rep 시작 기준 0부터). */
    val frameIndex: Int,
    val timestampMs: Long,
    val kneeAngle: Float?,
    val leftKneeAngle: Float?,
    val rightKneeAngle: Float?,
    val hipAngle: Float?,
    val torsoLeanDegrees: Float?,
    val kneeAsymmetryDegrees: Float?,
    /**
     * 무릎폭 ÷ 발목폭. 정면 촬영에서만 의미가 있다.
     *
     * [RepSummary.top]과 [RepSummary.bottom]에 모두 담기며, 두 값의 비로 무릎 정렬을
     * 판정한다([FormThresholds.kneeAlignmentOf]). 절대값 하나로는 판정할 수 없어
     * 두 시점이 모두 필요하다.
     */
    val kneeSpreadRatio: Float?,
    /**
     * 발 키포인트 원좌표. Halpe26 모델에서만 채워진다.
     *
     * 특징 수식이 확정되지 않았으므로 계산 결과가 아니라 좌표를 담는다.
     */
    val leftFoot: FootSample?,
    val rightFoot: FootSample?,
    /** 판정에 필요한 하체 키포인트 중 최저 confidence. 낮으면 이 시점 값을 신뢰할 수 없다. */
    val minLegConfidence: Float,
    /** 창에서 실제로 축약에 쓰인 프레임 수. */
    val framesUsed: Int,
)

/**
 * 한 발의 Halpe26 키포인트.
 *
 * 좌표는 **원본 프레임 기준 0~1 정규화 좌표**다 ([Keypoint]와 동일 좌표계).
 * confidence를 함께 담는 이유: AI Hub 데이터에는 confidence가 없어 모든 좌표가 유효로
 * 계산됐지만, 실제 모델은 발이 가려지거나 프레임을 벗어나면 낮은 값을 낸다. 낮은
 * confidence로 계산한 특징을 판정에 쓰면 조용히 틀린다.
 */
data class FootSample(
    val bigToeX: Float,
    val bigToeY: Float,
    val bigToeConfidence: Float,
    val smallToeX: Float,
    val smallToeY: Float,
    val smallToeConfidence: Float,
    val heelX: Float,
    val heelY: Float,
    val heelConfidence: Float,
    val ankleX: Float,
    val ankleY: Float,
    val ankleConfidence: Float,
) {
    /** 발 키포인트 4개 중 최저 confidence. */
    val minConfidence: Float
        get() = minOf(bigToeConfidence, smallToeConfidence, heelConfidence, ankleConfidence)

    companion object {
        /**
         * [Pose]에서 한 발을 추출한다. 발 키포인트가 없는 모델(MoveNet)에서는 발 좌표의
         * confidence가 0으로 채워지므로, 호출부가 [minConfidence]로 걸러낼 수 있다.
         */
        fun from(pose: Pose, side: Side): FootSample {
            val bigToe = pose[if (side == Side.LEFT) KeypointType.LEFT_BIG_TOE else KeypointType.RIGHT_BIG_TOE]
            val smallToe = pose[if (side == Side.LEFT) KeypointType.LEFT_SMALL_TOE else KeypointType.RIGHT_SMALL_TOE]
            val heel = pose[if (side == Side.LEFT) KeypointType.LEFT_HEEL else KeypointType.RIGHT_HEEL]
            val ankle = pose[if (side == Side.LEFT) KeypointType.LEFT_ANKLE else KeypointType.RIGHT_ANKLE]
            return FootSample(
                bigToeX = bigToe.x, bigToeY = bigToe.y, bigToeConfidence = bigToe.confidence,
                smallToeX = smallToe.x, smallToeY = smallToe.y, smallToeConfidence = smallToe.confidence,
                heelX = heel.x, heelY = heel.y, heelConfidence = heel.confidence,
                ankleX = ankle.x, ankleY = ankle.y, ankleConfidence = ankle.confidence,
            )
        }
    }

    enum class Side { LEFT, RIGHT }
}
