package com.mist.formchecker.poseengine

import kotlin.math.abs

/**
 * 한 프레임에서 계산한 자세 특징.
 *
 * ## 왜 프레임마다 계산하는가
 * 임계값은 시점 하나가 아니라 **동작 진행도 구간별**로 만들어야 한다(문서 §2.1). "하강
 * 70~80% 구간의 무릎 내측 이동 정상 범위"가 관리 단위다. 선 자세와 최저점 두 장만 남기면
 * 하강 중간에 무릎이 들어갔다 돌아온 것을 잡을 수 없고, 나중에 다른 시점을 보려면 다시
 * 촬영해야 한다.
 *
 * ## null의 뜻
 * **판정 불가**다. 원인은 두 가지 — 필요한 키포인트 confidence가 낮거나, 촬영 각도가 그
 * 항목을 지원하지 않거나. **추정값을 채우지 않는다.** 낮은 신뢰도를 잘못된 자세로 판정하면
 * 안 된다(문서 §2.3).
 *
 * ## 정규화
 * 모든 거리는 [StandingCalibration]의 길이로 나눠 무차원화한다. 픽셀이나 정규화 좌표를
 * 그대로 임계값에 쓰면 카메라 거리가 바뀔 때마다 값이 달라진다(문서 §8.3).
 */
data class FrameFeatures(
    // ── 공통 (양쪽 각도에서 계산 가능) ──────────────────────
    /** 무릎 굴곡각(도). 곧게 서면 0, 굽힐수록 증가. 좌/우. */
    val leftKneeFlexion: Float?,
    val rightKneeFlexion: Float?,
    /** 고관절 굴곡각(도). 좌/우. */
    val leftHipFlexion: Float?,
    val rightHipFlexion: Float?,
    /** 발목 배측굴곡(도). 기립 대비 변화량. 좌/우. */
    val leftAnkleDorsiflexion: Float?,
    val rightAnkleDorsiflexion: Float?,

    // ── 측면 특징 (문서 §11) ────────────────────────────────
    /**
     * 깊이 비율 `(hip.y − knee.y) / legLength`.
     *
     * **무릎 각도보다 직접적인 깊이 지표다.** "패럴렐"의 정의가 *대퇴가 수평 = 엉덩이가
     * 무릎 높이*이고, 무릎 각도는 대퇴/정강이 비율에 따라 같은 깊이에서도 값이 달라진다.
     *
     * 음수면 엉덩이가 무릎보다 높고, 0 근처면 같은 높이, 양수면 엉덩이가 더 낮다.
     */
    val depthRatio: Float?,
    /** 대퇴가 수평에서 이루는 각(도). 0이면 수평(패럴렐). */
    val thighAngle: Float?,
    /** 몸통 전경각(도) = `hip→neck` 벡터와 수직축의 각. */
    val trunkLean: Float?,
    /** 정강이 전경각(도) = `ankle→knee` 벡터와 수직축의 각. */
    val shinAngle: Float?,
    /** 엉덩이 하강량 `(hip.y − 기립 hip.y) / legLength`. */
    val hipDropRatio: Float?,
    /** 엉덩이 전후 이동량 `(hip.x − 기립 hip.x) / legLength`. 부호 있음. */
    val hipTravelRatio: Float?,
    /** 뒤꿈치 들림 `(기립 heel.y − heel.y) / footLength`. 양수면 들림. 좌/우. */
    val leftHeelRise: Float?,
    val rightHeelRise: Float?,

    // ── 정면 특징 (문서 §10) ────────────────────────────────
    /** 스탠스 너비 `발목 간격 / hipWidth`. */
    val stanceWidthRatio: Float?,
    /**
     * 무릎 내측 이동 `내측 편차 / legLength`. **양수 = 몸 중심 방향**. 좌/우.
     *
     * 힙–발목 기준선을 긋고 무릎 높이에서의 기대 x와 실제 무릎 x의 차를 본다(문서 §10.3).
     * 무릎폭/발목폭 비율과 달리 **어느 쪽 무릎이 얼마나** 들어갔는지 갈라진다.
     */
    val leftMedialKneeDisplacement: Float?,
    val rightMedialKneeDisplacement: Float?,
    /** 골반 중심 좌우 이동 `(hipCenter.x − ankleCenter.x) / 발목 간격`. */
    val hipShiftRatio: Float?,
    /** 골반 기울기(도). 좌우 힙을 잇는 선과 수평축의 각. */
    val pelvisTilt: Float?,
    /** 어깨 기울기(도). */
    val shoulderTilt: Float?,
    /** 몸통 좌우 기울기(도) = `hipCenter→neck` 벡터와 수직축의 각. */
    val trunkLateralLean: Float?,
    /**
     * 발 회전 대리값. `등방보정(toeCenter.x − heel.x) / footLength`. 부호 있음. 좌/우.
     *
     * 정면에서 heel→toe 벡터가 깊이축에 놓여 크게 단축되므로 **절대 각도는 낼 수 없다.**
     * 안쪽/거의 정면/정상/과도의 거친 구간 판정에만 쓴다.
     */
    val leftFootRotation: Float?,
    val rightFootRotation: Float?,

    /**
     * 이 프레임 특징들에 쓰인 키포인트 중 최저 confidence.
     *
     * 문서 §8.1 — 특징값에 쓰인 관절 중 가장 낮은 신뢰도를 그 특징의 신뢰도로 쓴다.
     * 여기서는 프레임 전체 대표값을 남기고, 개별 특징은 null 여부로 구분한다.
     */
    val minConfidence: Float,
) {
    /** 좌우 무릎 굴곡 차(도). 둘 다 있을 때만. */
    val kneeFlexionAsymmetry: Float?
        get() = pair(leftKneeFlexion, rightKneeFlexion) { l, r -> abs(l - r) }

    /** 좌우 중 대표 무릎 굴곡각. 진행도 계산에 쓴다. */
    val representativeKneeFlexion: Float?
        get() = when {
            leftKneeFlexion != null && rightKneeFlexion != null ->
                (leftKneeFlexion + rightKneeFlexion) / 2f
            else -> leftKneeFlexion ?: rightKneeFlexion
        }

    private inline fun pair(a: Float?, b: Float?, combine: (Float, Float) -> Float): Float? =
        if (a != null && b != null) combine(a, b) else null

    companion object {
        /**
         * 프레임 특징을 계산한다.
         *
         * @param activeSide 측면 촬영에서 카메라에 가까운 쪽. **세션 내내 고정한다** —
         *   프레임마다 신뢰도 높은 다리로 바꾸면 관절이 전환되며 각도가 튄다(문서 §4.3).
         *   정면에서는 null을 주면 좌우 모두 계산한다.
         */
        fun from(
            pose: Pose,
            aspectRatio: Float,
            calibration: StandingCalibration,
            view: CaptureView,
            activeSide: Side?,
            minConfidence: Float = StandingCalibration.MIN_CONFIDENCE,
        ): FrameFeatures {
            fun p(type: KeypointType) = Geometry.point(pose, type, aspectRatio)
            fun ok(type: KeypointType) = pose.isReliable(type, minConfidence)

            val legLength = calibration.legLength
            val footLength = calibration.footLength

            // ── 관절각 ──────────────────────────────────────
            fun kneeFlexion(side: Side) =
                Geometry.kneeFlexion(pose, side, aspectRatio, minConfidence)

            fun hipFlexion(side: Side) =
                Geometry.hipFlexion(pose, side, aspectRatio, minConfidence)

            fun dorsiflexion(side: Side) =
                Geometry.ankleAngle(pose, side, aspectRatio, minConfidence)
                    ?.let { calibration.standingAnkleAngle - it }

            // ── 측면 ────────────────────────────────────────
            // 측면 특징은 가까운 쪽 다리 하나로만 계산한다. 좌우 평균을 쓰면 가려진
            // 먼 쪽 추정이 섞여 값이 튄다.
            val sideOf = activeSide
            val sideHip = sideOf?.let { Geometry.hip(it) }
            val sideKnee = sideOf?.let { Geometry.knee(it) }
            val sideAnkle = sideOf?.let { Geometry.ankle(it) }

            val depthRatio = if (
                view.isSide && sideHip != null && sideKnee != null &&
                ok(sideHip) && ok(sideKnee) && legLength > Geometry.EPSILON
            ) {
                (p(sideHip).second - p(sideKnee).second) / legLength
            } else {
                null
            }

            val thighAngle = if (
                view.isSide && sideHip != null && sideKnee != null && ok(sideHip) && ok(sideKnee)
            ) {
                Geometry.angleFromHorizontal(p(sideHip), p(sideKnee))
            } else {
                null
            }

            val shinAngle = if (
                view.isSide && sideKnee != null && sideAnkle != null &&
                ok(sideKnee) && ok(sideAnkle)
            ) {
                Geometry.angleFromVertical(p(sideAnkle), p(sideKnee))
            } else {
                null
            }

            val hipCenter = if (ok(KeypointType.LEFT_HIP) && ok(KeypointType.RIGHT_HIP)) {
                Geometry.midpoint(p(KeypointType.LEFT_HIP), p(KeypointType.RIGHT_HIP))
            } else {
                null
            }

            val trunkLean = if (view.isSide && hipCenter != null && ok(KeypointType.NECK)) {
                Geometry.angleFromVertical(hipCenter, p(KeypointType.NECK))
            } else {
                null
            }

            val hipDropRatio = if (view.isSide && hipCenter != null && legLength > Geometry.EPSILON) {
                (hipCenter.second - calibration.standingHipY) / legLength
            } else {
                null
            }

            // 기립 x 기준선은 캘리브레이션에 없다(x는 사람이 프레임 안에서 어디 서 있는지에
            // 따라 달라져 세션 기준선으로 쓰기 어렵다). 대신 발목을 기준으로 잰다 —
            // 발은 스쿼트 중 고정돼 있어야 하므로 더 안정적이다.
            val hipTravelRatio = if (
                view.isSide && hipCenter != null && sideAnkle != null && ok(sideAnkle) &&
                legLength > Geometry.EPSILON
            ) {
                (hipCenter.first - p(sideAnkle).first) / legLength
            } else {
                null
            }

            fun heelRise(side: Side): Float? {
                if (!view.isSide || footLength <= Geometry.EPSILON) return null
                val heel = Geometry.heel(side)
                if (!ok(heel)) return null
                // y는 아래로 증가하므로 들리면 y가 작아진다.
                return (calibration.standingHeelY - pose[heel].y) / footLength
            }

            // ── 정면 ────────────────────────────────────────
            val ankleCenter = if (ok(KeypointType.LEFT_ANKLE) && ok(KeypointType.RIGHT_ANKLE)) {
                Geometry.midpoint(p(KeypointType.LEFT_ANKLE), p(KeypointType.RIGHT_ANKLE))
            } else {
                null
            }
            val ankleSpread = if (ok(KeypointType.LEFT_ANKLE) && ok(KeypointType.RIGHT_ANKLE)) {
                Geometry.distance(p(KeypointType.LEFT_ANKLE), p(KeypointType.RIGHT_ANKLE))
            } else {
                null
            }

            val stanceWidthRatio = if (
                view.isFront && ankleSpread != null && calibration.hipWidth > Geometry.EPSILON
            ) {
                ankleSpread / calibration.hipWidth
            } else {
                null
            }

            /**
             * 무릎 내측 이동. 힙–발목 기준선 대비 무릎의 수평 편차를 내측 양수로 통일한다.
             *
             * 화면 x 기준 편차의 부호는 좌우가 반대다 — 사람의 왼쪽 다리는 화면 오른쪽에
             * 있으므로(정면 마주봄) 내측 방향이 x 감소다. 그래서 쪽에 따라 부호를 뒤집는다.
             */
            fun medialKnee(side: Side): Float? {
                if (!view.isFront || legLength <= Geometry.EPSILON) return null
                val hip = Geometry.hip(side)
                val knee = Geometry.knee(side)
                val ankle = Geometry.ankle(side)
                if (!ok(hip) || !ok(knee) || !ok(ankle)) return null
                val deviation = Geometry.horizontalDeviationFromLine(
                    p(hip), p(ankle), p(knee),
                ) ?: return null
                // 정면 촬영에서 사람의 왼쪽은 화면 오른쪽(x 큼)에 나타난다. 내측 =
                // 화면 중심 방향 = 왼쪽 다리는 x 감소, 오른쪽 다리는 x 증가.
                val inwardSign = if (side == Side.LEFT) -1f else 1f
                return deviation * inwardSign / legLength
            }

            val hipShiftRatio = if (
                view.isFront && hipCenter != null && ankleCenter != null &&
                ankleSpread != null && ankleSpread > Geometry.EPSILON
            ) {
                (hipCenter.first - ankleCenter.first) / ankleSpread
            } else {
                null
            }

            val pelvisTilt = if (
                view.isFront && ok(KeypointType.LEFT_HIP) && ok(KeypointType.RIGHT_HIP)
            ) {
                Geometry.angleFromHorizontal(
                    p(KeypointType.LEFT_HIP), p(KeypointType.RIGHT_HIP),
                )
            } else {
                null
            }

            val shoulderTilt = if (
                view.isFront && ok(KeypointType.LEFT_SHOULDER) && ok(KeypointType.RIGHT_SHOULDER)
            ) {
                Geometry.angleFromHorizontal(
                    p(KeypointType.LEFT_SHOULDER), p(KeypointType.RIGHT_SHOULDER),
                )
            } else {
                null
            }

            val trunkLateralLean = if (
                view.isFront && hipCenter != null && ok(KeypointType.NECK)
            ) {
                Geometry.angleFromVertical(hipCenter, p(KeypointType.NECK))
            } else {
                null
            }

            fun footRotation(side: Side): Float? {
                if (!view.isFront || footLength <= Geometry.EPSILON) return null
                val heel = Geometry.heel(side)
                if (!ok(heel)) return null
                val toe = Geometry.toeCenter(pose, side, aspectRatio, minConfidence) ?: return null
                // 바깥 방향을 양수로 둔다. 사람의 왼쪽 발은 화면 오른쪽에 있으므로
                // 바깥 = x 증가.
                val outwardSign = if (side == Side.LEFT) 1f else -1f
                return (toe.first - p(heel).first) * outwardSign / footLength
            }

            return FrameFeatures(
                leftKneeFlexion = kneeFlexion(Side.LEFT),
                rightKneeFlexion = kneeFlexion(Side.RIGHT),
                leftHipFlexion = hipFlexion(Side.LEFT),
                rightHipFlexion = hipFlexion(Side.RIGHT),
                leftAnkleDorsiflexion = dorsiflexion(Side.LEFT),
                rightAnkleDorsiflexion = dorsiflexion(Side.RIGHT),
                depthRatio = depthRatio,
                thighAngle = thighAngle,
                trunkLean = trunkLean,
                shinAngle = shinAngle,
                hipDropRatio = hipDropRatio,
                hipTravelRatio = hipTravelRatio,
                leftHeelRise = heelRise(Side.LEFT),
                rightHeelRise = heelRise(Side.RIGHT),
                stanceWidthRatio = stanceWidthRatio,
                leftMedialKneeDisplacement = medialKnee(Side.LEFT),
                rightMedialKneeDisplacement = medialKnee(Side.RIGHT),
                hipShiftRatio = hipShiftRatio,
                pelvisTilt = pelvisTilt,
                shoulderTilt = shoulderTilt,
                trunkLateralLean = trunkLateralLean,
                leftFootRotation = footRotation(Side.LEFT),
                rightFootRotation = footRotation(Side.RIGHT),
                minConfidence = LOWER_BODY.minOf { pose[it].confidence },
            )
        }

        /** 자세 판정에 쓰이는 하체·몸통 키포인트. 프레임 대표 신뢰도의 근거. */
        private val LOWER_BODY = listOf(
            KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
            KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
            KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE,
        )
    }
}
