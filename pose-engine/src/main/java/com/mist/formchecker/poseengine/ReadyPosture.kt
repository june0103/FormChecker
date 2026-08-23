package com.mist.formchecker.poseengine

import kotlin.math.abs

/**
 * 준비 자세(선 자세) 점검 항목.
 *
 * ## 왜 [FormWarning]과 다른 열거형인가
 * 재는 순간과 고치는 방법이 다르다. [FormWarning]은 **동작 중**에 나오고 "다음 rep에서
 * 이렇게 해보세요"로 이어진다. 여기 있는 항목은 **동작 전**에 나오고 "지금 발을
 * 옮기세요"로 이어진다. 두 목록을 합치면 리포트에서 분모가 섞인다 — 준비 자세는 세션당
 * 한 번 재고, 동작 경고는 rep마다 잰다.
 *
 * ## 지원 각도
 * [STANCE_WIDTH]·[TOE_DIRECTION]·[WEIGHT_BALANCE]는 **정면 전용**이다. 측면에서는 두
 * 발목이 화면에서 겹쳐 간격이 무너지고, 두 어깨도 앞뒤로 겹친다
 * (`기술선택_기록.md` 46·47·48번의 정규화 실패와 같은 원인).
 */
enum class ReadyCheck {
    /**
     * 촬영 방향. 사용자가 고른 각도와 실제로 서 있는 방향이 맞는가.
     *
     * **가장 먼저 본다** — 이게 어긋나면 나머지 세 항목이 통째로 판정 불가가 된다.
     */
    FACING,

    /** 무릎을 곧게 펴고 섰는가. 기준선이 기립 자세인지를 결정한다. */
    KNEE_STRAIGHT,

    /** 발 간격이 어깨너비 정도인가. 정면 전용. */
    STANCE_WIDTH,

    /** 발끝이 바깥을 향하는가. 정면 전용. */
    TOE_DIRECTION,

    /** 체중이 한쪽으로 쏠리지 않았는가. 정면 전용. */
    WEIGHT_BALANCE,
}

/**
 * 준비 자세에서 발견한 문제. **우선순위 순으로 선언한다** — 화면이 순서를 다시 정하지
 * 않도록.
 *
 * 문구는 여기 두지 않는다. `FeedbackLabels`가 화면 문구를 독점하며, enum 이름이 그대로
 * 화면에 찍히는 사고를 막기 위한 것이다.
 */
enum class ReadyIssue(val check: ReadyCheck) {
    /** 정면을 골랐는데 옆으로 서 있다. */
    TURN_TO_FRONT(ReadyCheck.FACING),

    /** 측면을 골랐는데 정면으로 서 있다. */
    TURN_TO_SIDE(ReadyCheck.FACING),

    /** 정면도 측면도 아닌 비스듬한 방향. */
    FACE_SQUARELY(ReadyCheck.FACING),

    /** 무릎이 덜 펴졌다. */
    KNEES_BENT(ReadyCheck.KNEE_STRAIGHT),

    /** 발이 어깨보다 좁다. */
    STANCE_TOO_NARROW(ReadyCheck.STANCE_WIDTH),

    /** 발이 어깨보다 넓다. */
    STANCE_TOO_WIDE(ReadyCheck.STANCE_WIDTH),

    /** 발끝이 안쪽을 향한다. */
    TOES_INWARD(ReadyCheck.TOE_DIRECTION),

    /** 골반이 한쪽으로 쏠렸다. */
    WEIGHT_ON_ONE_SIDE(ReadyCheck.WEIGHT_BALANCE),
}

/**
 * 준비 자세 점검 결과.
 *
 * ## 이걸 왜 캘리브레이션에 붙이는가
 * 기준 자세 측정은 사용자가 **가만히 서 있는 유일한 구간**이다. 준비 자세 점검은 정적인
 * 자세를 보는 판정이므로 여기가 유일하게 안정적인 자리다. 동작 중에 보면 하강 첫 프레임과
 * 구분되지 않는다.
 *
 * 그리고 이 구간은 프레임이 수십 장이라 **중앙값으로 축약할 수 있다.** 실측 P009 세션에서
 * 발목 간격이 일부 프레임에서 무너져(p5 0.0402, 중앙값 2.21) 프레임 하나만 보면 발 간격이
 * 통째로 틀린 값을 냈다 — 중앙값은 그 프레임들을 흡수한다.
 *
 * ## 판정하지 못한 것을 반드시 남긴다
 * [notJudged]가 비어 있지 않은데 [issues]가 비었다고 "준비 자세 좋아요"라고 말하면, 재지도
 * 않은 것을 괜찮다고 말하는 것이다([SessionReport]의 `notJudged`와 같은 이유). 측면
 * 촬영에서는 세 항목이 통째로 빠진다.
 *
 * ## 값은 판정과 함께 남긴다
 * 개발용 화면이 원값을 띄워 기기에서 확인할 수 있어야 한다. 임계값을 바꿀 근거가
 * 화면에서 나온 적이 여러 번 있었다.
 */
data class ReadyPosture(
    /** 발목 간격 ÷ 어깨 너비. 1.0이 어깨너비다. 정면 전용. */
    val stanceWidthByShoulder: Float?,
    /** 골반 중심 좌우 쏠림 `(hipCenter.x − ankleCenter.x) / 발목 간격`. 부호 있음. 정면 전용. */
    val hipShiftRatio: Float?,
    /**
     * 발끝 방향 대리값 `(발끝중점.x − 뒤꿈치.x) / 발 길이`. **양수 = 바깥**. 정면 전용.
     *
     * 크기는 카메라 높이에 좌우되므로 **부호만 판정에 쓴다**
     * ([ReadyThresholds.minFootLengthRatio] 주석 참고).
     */
    val leftToeDirection: Float?,
    val rightToeDirection: Float?,
    /** 선 자세 무릎 굴곡(도). 좌우 중 **더 굽은 쪽**이다 — 한쪽만 굽어도 기립이 아니다. */
    val standingKneeFlexion: Float?,
    /** 프레임에서 추정한 실제 촬영 방향. */
    val estimatedView: CaptureQuality.EstimatedView?,
    /** 발견한 문제. [ReadyIssue] 선언 순서(우선순위)를 따른다. */
    val issues: List<ReadyIssue>,
    /** 판정하지 못한 항목과 그 이유. */
    val notJudged: Map<ReadyCheck, NotJudgedReason>,
    /** 축약에 쓴 프레임 수. 0이면 아직 아무것도 보지 못했다. */
    val framesUsed: Int,
) {
    /** 실제로 판정한 항목. */
    val judged: Set<ReadyCheck> get() = ReadyCheck.entries.toSet() - notJudged.keys

    /**
     * 판정은 했고 문제는 없나.
     *
     * 하나도 판정하지 못했으면 false다 — "문제 없음"과 "못 봤음"은 다르다.
     */
    val clean: Boolean get() = framesUsed > 0 && judged.isNotEmpty() && issues.isEmpty()

    /** 가장 먼저 고칠 것. */
    val topIssue: ReadyIssue? get() = issues.firstOrNull()

    /** 판정하지 못한 이유. */
    enum class NotJudgedReason {
        /** 이 촬영 각도에서는 계산 자체가 불가능하다. */
        CAMERA_ANGLE,

        /**
         * 각도는 맞았지만 필요한 키포인트가 부족했다.
         *
         * 발끝 방향에서는 "발이 너무 짧게 보인다"도 여기 포함된다 — 사용자가 할 일이
         * 같기 때문이다(카메라를 낮추거나 발이 잘 보이게 선다).
         */
        KEYPOINTS,
    }

    companion object {

        /** 아직 아무 프레임도 보지 못한 상태. */
        val EMPTY = ReadyPosture(
            stanceWidthByShoulder = null,
            hipShiftRatio = null,
            leftToeDirection = null,
            rightToeDirection = null,
            standingKneeFlexion = null,
            estimatedView = null,
            issues = emptyList(),
            notJudged = emptyMap(),
            framesUsed = 0,
        )

        /**
         * 여러 프레임을 중앙값으로 축약해 판정한다.
         *
         * @param frames (pose, frameAspectRatio). 한 장만 넘겨도 된다 — 준비 시간 동안
         *   실시간으로 보여줄 때가 그렇다. 프레임이 많을수록 값이 안정적이다.
         * @param cameraAngle 사용자가 고른 각도. 정면 전용 항목을 켤지가 여기서 갈린다.
         */
        fun from(
            frames: List<Pair<Pose, Float>>,
            cameraAngle: CameraAngle,
            form: FormThresholds = FormThresholds(),
            ready: ReadyThresholds = ReadyThresholds(),
            minConfidence: Float = StandingCalibration.MIN_CONFIDENCE,
        ): ReadyPosture {
            if (frames.isEmpty()) return EMPTY

            val front = cameraAngle == CameraAngle.FRONT
            val samples = frames.map { (pose, aspect) ->
                measure(pose, aspect, front, form, ready, minConfidence)
            }

            val stance = samples.medianOf { it.stanceWidth }
            val hipShift = samples.medianOf { it.hipShift }
            val leftToe = samples.medianOf { it.leftToe }
            val rightToe = samples.medianOf { it.rightToe }
            val kneeFlexion = samples.medianOf { it.kneeFlexion }
            val view = samples.mapNotNull { it.view }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key

            val facingIssue = facingIssueOf(cameraAngle, view)
            val issues = buildList {
                facingIssue?.let(::add)
                if (kneeFlexion != null && kneeFlexion > ready.maxStandingKneeFlexion) {
                    add(ReadyIssue.KNEES_BENT)
                }
                // 방향이 어긋나면 정면 특징은 계산됐어도 신뢰할 수 없다. 값이 나온다고
                // 판정하면 옆으로 선 사람에게 "발을 벌리세요"라고 말한다.
                if (facingIssue == null) {
                    if (stance != null) {
                        when {
                            stance < ready.stanceNarrowLimit -> add(ReadyIssue.STANCE_TOO_NARROW)
                            stance > ready.stanceWideLimit -> add(ReadyIssue.STANCE_TOO_WIDE)
                        }
                    }
                    // 한쪽만 안쪽을 향해도 오류다. 부호만 본다.
                    if (listOfNotNull(leftToe, rightToe).any { it < 0f }) {
                        add(ReadyIssue.TOES_INWARD)
                    }
                    // 부호는 어느 쪽으로 쏠렸는지만 알려주므로 크기로 판정한다.
                    if (hipShift != null && abs(hipShift) > form.hipShiftLimit) {
                        add(ReadyIssue.WEIGHT_ON_ONE_SIDE)
                    }
                }
            }.sortedBy { it.ordinal }

            return ReadyPosture(
                stanceWidthByShoulder = stance,
                hipShiftRatio = hipShift,
                leftToeDirection = leftToe,
                rightToeDirection = rightToe,
                standingKneeFlexion = kneeFlexion,
                estimatedView = view,
                issues = issues,
                notJudged = notJudgedOf(
                    front = front,
                    facingMismatch = facingIssue != null,
                    view = view,
                    stance = stance,
                    toes = listOfNotNull(leftToe, rightToe),
                    hipShift = hipShift,
                    kneeFlexion = kneeFlexion,
                ),
                framesUsed = frames.size,
            )
        }

        /**
         * 고른 각도와 추정 방향이 다른가.
         *
         * 추정하지 못했으면(null) 문제로 보지 않는다 — 몸통이 짧게 보이거나 어깨가 가려진
         * 경우이고, 그건 방향 문제가 아니라 관측 문제다. 없는 근거로 "돌아서세요"라고
         * 말하면 사용자가 안내 전체를 무시하게 된다.
         */
        private fun facingIssueOf(
            cameraAngle: CameraAngle,
            view: CaptureQuality.EstimatedView?,
        ): ReadyIssue? = when {
            view == null -> null
            view == CaptureQuality.EstimatedView.AMBIGUOUS -> ReadyIssue.FACE_SQUARELY
            cameraAngle == CameraAngle.FRONT && view == CaptureQuality.EstimatedView.SIDE ->
                ReadyIssue.TURN_TO_FRONT
            cameraAngle == CameraAngle.SIDE && view == CaptureQuality.EstimatedView.FRONT ->
                ReadyIssue.TURN_TO_SIDE
            else -> null
        }

        private fun notJudgedOf(
            front: Boolean,
            facingMismatch: Boolean,
            view: CaptureQuality.EstimatedView?,
            stance: Float?,
            toes: List<Float>,
            hipShift: Float?,
            kneeFlexion: Float?,
        ): Map<ReadyCheck, NotJudgedReason> = buildMap {
            if (view == null) put(ReadyCheck.FACING, NotJudgedReason.KEYPOINTS)
            if (kneeFlexion == null) put(ReadyCheck.KNEE_STRAIGHT, NotJudgedReason.KEYPOINTS)

            // 정면 전용 세 항목. 각도가 안 맞으면 계산 자체가 불가능하고, 방향이
            // 어긋났으면 값이 나와도 쓸 수 없다 — 사용자가 할 일은 둘 다 "각도를 맞춰라"다.
            val frontReason = when {
                !front -> NotJudgedReason.CAMERA_ANGLE
                facingMismatch -> NotJudgedReason.CAMERA_ANGLE
                else -> null
            }
            fun frontCheck(check: ReadyCheck, measured: Boolean) {
                when {
                    frontReason != null -> put(check, frontReason)
                    !measured -> put(check, NotJudgedReason.KEYPOINTS)
                }
            }
            frontCheck(ReadyCheck.STANCE_WIDTH, stance != null)
            frontCheck(ReadyCheck.TOE_DIRECTION, toes.isNotEmpty())
            frontCheck(ReadyCheck.WEIGHT_BALANCE, hipShift != null)
        }

        /** 한 프레임에서 잰 값들. 못 잰 것은 null이고, 축약에서 제외된다. */
        private class Sample(
            val stanceWidth: Float?,
            val hipShift: Float?,
            val leftToe: Float?,
            val rightToe: Float?,
            val kneeFlexion: Float?,
            val view: CaptureQuality.EstimatedView?,
        )

        private fun measure(
            pose: Pose,
            aspect: Float,
            front: Boolean,
            form: FormThresholds,
            ready: ReadyThresholds,
            minConfidence: Float,
        ): Sample {
            fun p(type: KeypointType) = Geometry.point(pose, type, aspect)
            fun ok(type: KeypointType) = pose.isReliable(type, minConfidence)

            val ankleSpread = if (ok(KeypointType.LEFT_ANKLE) && ok(KeypointType.RIGHT_ANKLE)) {
                Geometry.distance(p(KeypointType.LEFT_ANKLE), p(KeypointType.RIGHT_ANKLE))
            } else {
                null
            }
            // 발목 간격은 정면 세 항목 중 둘의 분모다. 두 발목이 화면에서 겹치면 작은
            // 분모가 값을 통째로 부풀린다 — 동작 중 판정과 같은 하한을 쓴다.
            val usableSpread = ankleSpread?.takeIf { it >= form.minStanceWidth }

            val shoulderWidth = if (
                ok(KeypointType.LEFT_SHOULDER) && ok(KeypointType.RIGHT_SHOULDER)
            ) {
                Geometry.distance(p(KeypointType.LEFT_SHOULDER), p(KeypointType.RIGHT_SHOULDER))
            } else {
                null
            }

            val stanceWidth = if (
                front && usableSpread != null &&
                shoulderWidth != null && shoulderWidth > Geometry.EPSILON
            ) {
                usableSpread / shoulderWidth
            } else {
                null
            }

            val hipCenter = if (ok(KeypointType.LEFT_HIP) && ok(KeypointType.RIGHT_HIP)) {
                Geometry.midpoint(p(KeypointType.LEFT_HIP), p(KeypointType.RIGHT_HIP))
            } else {
                null
            }
            val ankleCenter = if (ankleSpread != null) {
                Geometry.midpoint(p(KeypointType.LEFT_ANKLE), p(KeypointType.RIGHT_ANKLE))
            } else {
                null
            }
            val hipShift = if (front && hipCenter != null && ankleCenter != null && usableSpread != null) {
                (hipCenter.first - ankleCenter.first) / usableSpread
            } else {
                null
            }

            /**
             * 발끝 방향. 바깥을 양수로 둔다.
             *
             * 분모를 캘리브레이션이 아니라 **그 프레임의 발 길이**로 쓴다 — 준비 시간에는
             * 아직 기준선이 없고, 이 판정은 부호만 쓰므로 분모의 정밀도가 필요 없다.
             * 그래도 나누는 이유는 개발 화면에서 크기를 비교할 수 있게 하기 위함이다.
             */
            fun toeDirection(side: Side): Float? {
                if (!front) return null
                val heel = Geometry.heel(side)
                if (!ok(heel)) return null
                val toe = Geometry.toeCenter(pose, side, aspect, minConfidence) ?: return null
                val footLength = Geometry.footLength(pose, side, aspect, minConfidence) ?: return null
                val legLength = legLengthOf(pose, side, aspect, minConfidence) ?: return null
                // 발이 너무 짧게 보이면 좌우 성분이 노이즈와 구분되지 않는다.
                if (footLength / legLength < ready.minFootLengthRatio) return null
                // 정면에서 사람의 왼발은 화면 오른쪽에 있으므로 바깥 = x 증가.
                val outwardSign = if (side == Side.LEFT) 1f else -1f
                return (toe.first - p(heel).first) * outwardSign / footLength
            }

            // 좌우 중 더 굽은 쪽. 측면에서는 가려진 쪽이 null이 되어 자동으로 한쪽만 남는다.
            val kneeFlexion = listOf(Side.LEFT, Side.RIGHT)
                .mapNotNull { Geometry.kneeFlexion(pose, it, aspect, minConfidence) }
                .maxOrNull()

            return Sample(
                stanceWidth = stanceWidth,
                hipShift = hipShift,
                leftToe = toeDirection(Side.LEFT),
                rightToe = toeDirection(Side.RIGHT),
                kneeFlexion = kneeFlexion,
                view = CaptureQuality.estimateView(pose, aspect, minConfidence = minConfidence),
            )
        }

        private fun legLengthOf(
            pose: Pose,
            side: Side,
            aspect: Float,
            minConfidence: Float,
        ): Float? {
            val joints = listOf(Geometry.hip(side), Geometry.knee(side), Geometry.ankle(side))
            if (joints.any { !pose.isReliable(it, minConfidence) }) return null
            fun p(type: KeypointType) = Geometry.point(pose, type, aspect)
            val length = Geometry.distance(p(joints[0]), p(joints[1])) +
                Geometry.distance(p(joints[1]), p(joints[2]))
            return length.takeIf { it > Geometry.EPSILON }
        }

        /**
         * 중앙값. 평균이 아닌 이유는 [ReadyPosture] 주석의 P009 사례다 — 일부 프레임이
         * 물리적으로 불가능한 값을 내면 평균은 끌려가고 중앙값은 버틴다.
         */
        private inline fun List<Sample>.medianOf(select: (Sample) -> Float?): Float? {
            val values = mapNotNull(select).sorted()
            if (values.isEmpty()) return null
            val middle = values.size / 2
            return if (values.size % 2 == 1) {
                values[middle]
            } else {
                (values[middle - 1] + values[middle]) / 2f
            }
        }
    }
}
