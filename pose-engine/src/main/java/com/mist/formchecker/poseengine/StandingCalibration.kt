package com.mist.formchecker.poseengine

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 기립 자세 캘리브레이션 결과.
 *
 * ## 왜 필요한가
 * **모든 거리 특징의 분모가 여기서 나온다.** 픽셀 거리를 그대로 임계값으로 쓰면 카메라에서
 * 2m 떨어져 찍은 사람과 4m 떨어져 찍은 사람이 다른 값을 낸다. 다리 길이·골반 너비·발 길이로
 * 나눠 무차원화해야 촬영 조건이 바뀌어도 같은 자세가 같은 숫자를 낸다.
 *
 * ## 왜 체절 길이를 따로 저장하는가
 * 대퇴와 정강이의 **비율**이 사람마다 다르고, 그 비율이 같은 깊이에서의 무릎 각도를 바꾼다.
 * 다리 길이 합만 저장하면 "무릎각이 큰 것이 얕게 앉은 것인지 대퇴가 긴 것인지" 구분할 수 없다.
 *
 * ## 좌표계
 * 모든 길이는 **등방 보정된 정규화 좌표** 기준이다(x에 종횡비를 곱한 뒤 계산).
 * 해상도가 달라도 같은 값이 나오지만, 카메라 거리가 바뀌면 값이 바뀐다 — 그래서 세션마다
 * 다시 잰다.
 */
data class StandingCalibration(
    /** 대퇴 길이(힙→무릎). 좌우 평균. */
    val femurLength: Float,
    /** 정강이 길이(무릎→발목). 좌우 평균. */
    val tibiaLength: Float,
    /** 골반 너비(좌우 힙 간격). */
    val hipWidth: Float,
    /** 어깨 너비. */
    val shoulderWidth: Float,
    /** 몸통 길이(어깨 중점→힙 중점). */
    val trunkLength: Float,
    /** 발 길이(뒤꿈치→발끝 중점). 좌우 평균. */
    val footLength: Float,
    /** 기립 무릎 굴곡각(도). 곧게 서면 0에 가깝다. */
    val standingKneeFlexion: Float,
    /**
     * 기립 발목각(도) = `angle(무릎, 발목, 발끝중점)`.
     *
     * 발목 배측굴곡은 절대 각도가 아니라 **이 값 대비 변화량**으로 잰다(문서 §9).
     * 발목 각도의 절대값은 발 길이 추정과 신발에 크게 좌우되기 때문이다.
     */
    val standingAnkleAngle: Float,
    /** 기립 골반 높이(정규화 y). 하강량의 기준선. */
    val standingHipY: Float,
    /** 기립 뒤꿈치 높이(정규화 y). 뒤꿈치 들림의 기준선. */
    val standingHeelY: Float,
    /** 평균한 프레임 수. */
    val framesUsed: Int,
    /**
     * 프레임 간 좌표 흔들림(정규화 좌표 표준편차의 최댓값).
     *
     * 이 값이 크면 사람이 움직였거나 추정이 불안정한 것이다. 기준선 자체가 흔들리면
     * 이후 모든 특징이 흔들린다.
     */
    val jitter: Float,
) {
    /** 다리 전체 길이. 대부분의 변위 특징의 분모다. */
    val legLength: Float get() = femurLength + tibiaLength

    /**
     * 대퇴/정강이 비율.
     *
     * 같은 깊이에서의 무릎 각도를 바꾸는 체형 변수다. 임계값을 다른 사람에게 옮길 때
     * 이 값이 크게 다르면 그대로 쓸 수 없다는 신호가 된다.
     */
    val femurTibiaRatio: Float get() = femurLength / tibiaLength

    companion object {
        /** 기립 캘리브레이션에 필요한 키포인트. 하나라도 없으면 캘리브레이션 불가. */
        val REQUIRED: List<KeypointType> = listOf(
            KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
            KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
            KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
            KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE,
        )

        /**
         * 여러 프레임을 평균해 기준선을 만든다.
         *
         * ## 왜 여러 프레임인가
         * 기립 자세는 **정적**이라 프레임을 평균할 수 있다. 이것이 동작 중 판정과 결정적으로
         * 다른 점이다 — 노이즈를 원하는 만큼 줄일 수 있다. 프레임 하나로 재면 그 프레임의
         * 추정 오차가 이후 모든 특징의 분모에 그대로 박힌다.
         *
         * @param frames 기립 구간의 프레임들. 각각 (pose, frameAspectRatio).
         * @return 필수 키포인트가 없거나 표본이 부족하면 null.
         */
        fun from(
            frames: List<Pair<Pose, Float>>,
            minConfidence: Float = MIN_CONFIDENCE,
            minFrames: Int = MIN_FRAMES,
        ): StandingCalibration? {
            val usable = frames.filter { (pose, _) ->
                REQUIRED.all { pose.isReliable(it, minConfidence) }
            }
            if (usable.size < minFrames) return null

            // 프레임마다 값을 재고 마지막에 평균한다. 좌표를 먼저 평균하면 사람이 조금
            // 움직였을 때 실제로 존재하지 않은 자세의 길이를 재게 된다.
            val samples = usable.map { (pose, aspect) -> measure(pose, aspect, minConfidence) }

            return StandingCalibration(
                femurLength = samples.avg { it.femur },
                tibiaLength = samples.avg { it.tibia },
                hipWidth = samples.avg { it.hipWidth },
                shoulderWidth = samples.avg { it.shoulderWidth },
                trunkLength = samples.avg { it.trunkLength },
                footLength = samples.avg { it.footLength },
                standingKneeFlexion = samples.avg { it.kneeFlexion },
                standingAnkleAngle = samples.avg { it.ankleAngle },
                standingHipY = samples.avg { it.hipY },
                standingHeelY = samples.avg { it.heelY },
                framesUsed = samples.size,
                jitter = jitterOf(samples),
            )
        }

        /**
         * 카메라에 가까운 쪽 다리를 정한다.
         *
         * ## 왜 캘리브레이션 창에서 정하는가
         * 활성측은 **세션 내내 고정해야 한다**(문서 §4.3) — 프레임마다 신뢰도 높은 쪽으로
         * 갈아타면 관절 좌표가 전환되며 각도가 크게 튄다. 그러면 언제 정하느냐가 문제인데,
         * 캘리브레이션 창은 정적이고 프레임이 수십 장이라 **판단이 가장 안정적인 유일한
         * 지점**이다. 동작 중에 정하면 그 순간의 가림 상태에 좌우된다.
         *
         * 가까운 쪽이 신뢰도가 높다 — 먼 쪽은 몸에 가려져 추정값이 된다. 실측에서 먼 쪽
         * 힙의 신뢰도 중앙값이 0.518로 하한 0.5를 겨우 넘었다.
         *
         * ## 왜 평균인가
         * 한 프레임의 신뢰도는 흔들린다. 정적 구간이므로 평균해서 노이즈를 줄일 수 있고,
         * 그게 이 창을 쓰는 이유다. 중앙값을 쓰지 않는 이유는 이상치를 걸러낼 필요가 없기
         * 때문이다 — 여기서는 어느 쪽이 큰지만 알면 된다.
         *
         * @return 프레임이 없으면 null. 좌우가 같으면 [Side.LEFT].
         */
        fun nearerSide(frames: List<Pair<Pose, Float>>): Side? {
            if (frames.isEmpty()) return null

            fun meanConfidence(side: Side): Double {
                val joints = listOf(Geometry.hip(side), Geometry.knee(side), Geometry.ankle(side))
                return frames.sumOf { (pose, _) ->
                    joints.sumOf { pose[it].confidence.toDouble() } / joints.size
                } / frames.size
            }

            return if (meanConfidence(Side.LEFT) >= meanConfidence(Side.RIGHT)) {
                Side.LEFT
            } else {
                Side.RIGHT
            }
        }

        /** 한 프레임에서 잰 값들. */
        private class Sample(
            val femur: Float,
            val tibia: Float,
            val hipWidth: Float,
            val shoulderWidth: Float,
            val trunkLength: Float,
            val footLength: Float,
            val kneeFlexion: Float,
            val ankleAngle: Float,
            val hipY: Float,
            val heelY: Float,
        )

        private fun measure(pose: Pose, aspect: Float, minConfidence: Float): Sample {
            fun p(type: KeypointType) = Geometry.point(pose, type, aspect)

            val leftFemur = Geometry.distance(p(KeypointType.LEFT_HIP), p(KeypointType.LEFT_KNEE))
            val rightFemur = Geometry.distance(p(KeypointType.RIGHT_HIP), p(KeypointType.RIGHT_KNEE))
            val leftTibia = Geometry.distance(p(KeypointType.LEFT_KNEE), p(KeypointType.LEFT_ANKLE))
            val rightTibia =
                Geometry.distance(p(KeypointType.RIGHT_KNEE), p(KeypointType.RIGHT_ANKLE))

            val shoulderCenter = Geometry.midpoint(
                p(KeypointType.LEFT_SHOULDER), p(KeypointType.RIGHT_SHOULDER),
            )
            val hipCenter = Geometry.midpoint(p(KeypointType.LEFT_HIP), p(KeypointType.RIGHT_HIP))

            // 발 길이는 신뢰 가능한 발만 쓴다. 측면에서는 먼 쪽 발이 가려진다.
            val footLengths = listOf(Side.LEFT, Side.RIGHT).mapNotNull { side ->
                Geometry.footLength(pose, side, aspect, minConfidence)
            }
            // 발 좌표가 없으면 0으로 둔다 — 뒤꿈치 특징만 계산 불가가 되고 나머지는 유효하다.
            val footLength = if (footLengths.isEmpty()) 0f else footLengths.average().toFloat()

            val kneeFlexions = listOf(Side.LEFT, Side.RIGHT).mapNotNull {
                Geometry.kneeFlexion(pose, it, aspect, minConfidence)
            }
            val ankleAngles = listOf(Side.LEFT, Side.RIGHT).mapNotNull {
                Geometry.ankleAngle(pose, it, aspect, minConfidence)
            }
            val heels = listOf(KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL)
                .filter { pose.isReliable(it, minConfidence) }
                .map { pose[it].y }

            return Sample(
                femur = (leftFemur + rightFemur) / 2f,
                tibia = (leftTibia + rightTibia) / 2f,
                hipWidth = Geometry.distance(
                    p(KeypointType.LEFT_HIP), p(KeypointType.RIGHT_HIP),
                ),
                shoulderWidth = Geometry.distance(
                    p(KeypointType.LEFT_SHOULDER), p(KeypointType.RIGHT_SHOULDER),
                ),
                trunkLength = Geometry.distance(shoulderCenter, hipCenter),
                footLength = footLength,
                kneeFlexion = if (kneeFlexions.isEmpty()) 0f else kneeFlexions.average().toFloat(),
                ankleAngle = if (ankleAngles.isEmpty()) 0f else ankleAngles.average().toFloat(),
                hipY = hipCenter.second,
                heelY = if (heels.isEmpty()) 0f else heels.average().toFloat(),
            )
        }

        /**
         * 흔들림 = 길이 지표들의 표준편차 중 최댓값.
         *
         * 길이는 사람이 가만히 서 있으면 변하지 않아야 한다. 변한다면 추정이 불안정하거나
         * 사람이 움직인 것이고, 그 기준선으로 만든 특징은 신뢰할 수 없다.
         */
        private fun jitterOf(samples: List<Sample>): Float = listOf(
            samples.map { it.femur }, samples.map { it.tibia },
            samples.map { it.hipWidth }, samples.map { it.trunkLength },
        ).maxOf { values -> stdDev(values) }

        private fun stdDev(values: List<Float>): Float {
            if (values.size < 2) return 0f
            val mean = values.average()
            return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
                .toFloat()
        }

        private inline fun List<Sample>.avg(select: (Sample) -> Float): Float =
            map(select).average().toFloat()

        /** 문서 §8.1의 하한. 이 값 이상이면 특징 계산에 쓴다. */
        const val MIN_CONFIDENCE = 0.5f

        /**
         * 최소 프레임 수. 20fps에서 약 1초.
         *
         * 문서 §6이 2~3초를 요구하지만, 그중 필수 키포인트가 모두 보인 프레임만 쓰므로
         * 하한은 더 낮게 둔다. 실제 요구 시간은 화면에서 통제한다.
         */
        const val MIN_FRAMES = 20

        // ── 통과 조건 ────────────────────────────────────────
        /** 다리 길이가 이보다 짧으면 사람이 너무 작게 찍혔다 (문서 §5.1: 화면의 70~85%). */
        private const val MIN_LEG_LENGTH = 0.15f

        /** 길이 지표 표준편차 상한. 넘으면 사람이 움직였거나 추정이 불안정하다. */
        private const val MAX_JITTER = 0.01f

        /** 기립 무릎 굴곡 상한. 넘으면 곧게 선 자세가 아니다. */
        private const val MAX_STANDING_FLEXION = 20f
    }

    /**
     * 캘리브레이션 통과 조건 (문서 §6.2).
     *
     * 실패하면 자세 피드백을 시작하지 않고 카메라 위치를 조정하도록 안내한다 — 기준선이
     * 잘못되면 이후 모든 특징이 조용히 틀린다.
     */
    fun validate(): Result {
        val problems = buildList {
            if (framesUsed < MIN_FRAMES) add(Problem.TOO_FEW_FRAMES)
            // 다리 길이가 화면의 15% 미만이면 사람이 너무 작게 찍혔다. 문서 §5.1이
            // 화면 높이의 70~85%를 차지하라고 요구한다.
            if (legLength < MIN_LEG_LENGTH) add(Problem.SUBJECT_TOO_SMALL)
            if (jitter > MAX_JITTER) add(Problem.UNSTABLE)
            // 곧게 서지 않았으면 기준선이 기립 자세가 아니다.
            if (standingKneeFlexion > MAX_STANDING_FLEXION) add(Problem.NOT_STANDING_STRAIGHT)
            if (footLength <= 0f) add(Problem.FOOT_NOT_VISIBLE)
        }
        return Result(problems)
    }

    data class Result(val problems: List<Problem>) {
        val passed: Boolean get() = problems.none { it.blocking }
    }

    enum class Problem(val message: String, val blocking: Boolean) {
        TOO_FEW_FRAMES("자세를 유지한 시간이 짧습니다 — 다시 시도해 주세요", true),
        SUBJECT_TOO_SMALL("카메라를 더 가까이 두세요 — 전신이 화면의 70% 이상을 차지해야 합니다", true),
        UNSTABLE("움직임이 감지됐습니다 — 그대로 서 계세요", true),
        NOT_STANDING_STRAIGHT("무릎을 곧게 펴고 서 주세요", true),

        /**
         * 발이 안 보이는 것은 **차단하지 않는다.**
         *
         * 뒤꿈치 들림·발끝 방향 특징만 못 쓰게 되고, 깊이·무릎·엉덩이 특징은 그대로
         * 유효하다. 여기서 차단하면 측면 촬영에서 먼 쪽 발이 가려질 때마다 수집이 막힌다.
         */
        FOOT_NOT_VISIBLE("발이 보이지 않습니다 — 발 관련 항목은 기록되지 않습니다", false),
    }

}

/** 좌우 구분. [FootSample.Side]와 별개로 pose-engine 전역에서 쓴다. */
enum class Side { LEFT, RIGHT }
