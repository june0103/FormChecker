package com.mist.formchecker.poseengine

import kotlin.math.abs
import kotlin.math.max

/**
 * 수집 세션 하나를 기록한다. 프레임마다 [accept]를 호출하면 단계·진행도를 붙여 쌓고,
 * rep이 끝날 때마다 요약을 만든다.
 *
 * ## 왜 [RepStateMachine]을 다시 만들지 않는가
 * 카운팅 상태 전이는 이미 검증된 [RepStateMachine]이 한다. 이 클래스는 그 위에서
 * **기록에 필요한 것**만 더한다 — 단계 라벨, 진행도, 프레임 누적, rep 요약.
 * 문서 §2.2가 "실시간과 촬영 영상은 분석 엔진을 공유한다"고 요구하는 것과 같은 이유로,
 * 수집 모드와 운동 모드도 같은 상태머신을 쓴다.
 *
 * ## 진행도를 무릎 굴곡으로 정규화하는 이유
 * 임계값이 진행도 구간별로 관리되므로(문서 §2.1) 프레임마다 진행도가 있어야 한다.
 * **시간이 아니라 무릎 굴곡 기준**이다(문서 §15.1) — 사람마다 스쿼트 속도가 달라 시간으로
 * 맞추면 같은 진행도가 다른 자세를 가리킨다.
 *
 * ```
 * progress = (현재 굴곡 − 상단 굴곡) / (최저점 굴곡 − 상단 굴곡)
 * ```
 *
 * ⚠ 하강 중에는 최저점 굴곡을 아직 모른다. 그래서 **실시간 진행도는 추정치**이고,
 * rep이 끝난 뒤 [finish]에서 실제 최저점으로 다시 계산한다. 기록에 남는 값은 재계산된
 * 것이다 — 임계값 생성에는 정확한 진행도가 필요하다.
 *
 * 스레드 안전하지 않다. 단일 분석 스레드에서만 호출할 것.
 */
class CaptureRecorder(
    private val info: CaptureSessionInfo,
    private val calibration: StandingCalibration,
    private val counting: CountingThresholds = CountingThresholds(),
) {
    private val frames = mutableListOf<MutableFrame>()
    private val completedReps = mutableListOf<CaptureRep>()

    private val stateMachine = RepStateMachine(
        thresholds = counting,
        cameraAngle = { if (info.view.isFront) CameraAngle.FRONT else CameraAngle.SIDE },
    )
    private val kneeEma = AngleEma(SmoothingConfig().emaTimeConstantMs)
    private var kneeMedian = MedianWindow(
        MedianWindow.sizeFor(SmoothingConfig().medianWindowMs, ASSUMED_FPS),
    )

    private var frameCounter = 0
    private var currentRepId = 0
    /** 현재 rep의 프레임 인덱스 범위. rep 밖이면 null. */
    private var repStartIndex: Int? = null
    private var standingFlexion: Float? = null

    val recordedFrames: Int get() = frames.size
    val recordedReps: Int get() = completedReps.size

    /**
     * 프레임 하나를 받는다.
     *
     * @param rawPose 필터 전 좌표. 재계산을 위해 그대로 보관한다.
     * @param filteredPose 필터 후 좌표. 특징 계산에 쓴다.
     * @return 이 프레임에서 rep이 완주됐으면 그 요약. 없으면 null.
     */
    fun accept(
        rawPose: Pose,
        filteredPose: Pose,
        timestampMs: Long,
    ): CaptureRep? {
        val aspect = info.aspectRatio
        val features = FrameFeatures.from(
            pose = filteredPose,
            aspectRatio = aspect,
            calibration = calibration,
            view = info.view,
            activeSide = info.view.activeSide,
        )

        // 상태머신은 무릎 각도 시계열만 본다. 굴곡각(0=곧게 섬) 대신 내각을 쓰는 이유:
        // RepStateMachine의 임계값이 내각 기준(155/145/130)으로 이미 검증됐다.
        val interiorAngle = features.representativeKneeFlexion?.let { 180f - it }
        val repFrame = RepFrame(
            timestampMs = timestampMs,
            rawKneeAngle = interiorAngle,
            smoothedKneeAngle = kneeEma.update(interiorAngle, timestampMs),
            medianKneeAngle = kneeMedian.push(interiorAngle),
            leftKneeAngle = features.leftKneeFlexion?.let { 180f - it },
            rightKneeAngle = features.rightKneeFlexion?.let { 180f - it },
            hipAngle = features.leftHipFlexion?.let { 180f - it },
            torsoLeanDegrees = features.trunkLean,
            kneeAsymmetryDegrees = features.kneeFlexionAsymmetry,
            kneeSpreadRatio = null,
            leftFoot = FootSample.from(filteredPose, FootSample.Side.LEFT),
            rightFoot = FootSample.from(filteredPose, FootSample.Side.RIGHT),
            // 힙·무릎 좌표를 RepFrame에 넣지 않는다 — 상태머신은 무릎 각도만 쓰고,
            // 좌표는 CaptureFrame이 26점 전부 보관한다.
            minLegConfidence = features.minConfidence,
        )
        val event = stateMachine.update(repFrame)

        // 상태머신 상태를 기록용 단계 라벨로 옮긴다.
        val phase = when (stateMachine.state) {
            RepState.IDLE -> CapturePhase.IDLE
            RepState.STANDING -> CapturePhase.READY
            RepState.DESCENDING -> CapturePhase.DESCENDING
            RepState.BOTTOM -> CapturePhase.BOTTOM
            RepState.ASCENDING -> CapturePhase.ASCENDING
        }

        // 선 자세 굴곡각을 기준선으로 갱신한다. 진행도의 분모 한쪽이다.
        if (phase == CapturePhase.READY) {
            features.representativeKneeFlexion?.let { flexion ->
                standingFlexion = standingFlexion?.let { kotlin.math.min(it, flexion) } ?: flexion
            }
        }

        if (event is RepEvent.Started) {
            currentRepId++
            repStartIndex = frames.size
        }

        frames += MutableFrame(
            frameIndex = frameCounter++,
            timestampMs = timestampMs,
            repId = if (phase.inRep) currentRepId else null,
            phase = phase,
            rawKeypoints = rawPose.keypoints.toList(),
            filteredKeypoints = filteredPose.keypoints.toList(),
            features = features,
        )

        return when (event) {
            is RepEvent.Completed -> finishRep(event.summary, counted = true)
            is RepEvent.Aborted -> {
                // 중단된 rep도 프레임은 남긴다. 카운팅 임계값을 검증하려면 negative가
                // 필요하다 — 무엇이 세어지지 않았는지가 데이터에 있어야 한다.
                finishRep(summary = null, counted = false)
            }
            else -> null
        }
    }

    /**
     * rep 하나를 마감한다. 진행도를 실제 최저점으로 다시 계산한다.
     *
     * 하강 중에는 최저점을 모르므로 실시간 진행도는 추정치다. 임계값 생성에는 정확한
     * 값이 필요하므로 여기서 덮어쓴다.
     */
    private fun finishRep(summary: RepSummary?, counted: Boolean): CaptureRep? {
        val start = repStartIndex ?: return null
        repStartIndex = null
        val repFrames = frames.subList(start, frames.size).filter { it.repId == currentRepId }
        if (repFrames.isEmpty()) return null

        val top = standingFlexion ?: repFrames.minOf { it.flexionOrMax() }
        val bottom = repFrames.maxOf { it.flexionOrMin() }
        val span = bottom - top

        val bottomIndex = repFrames.indexOfFirst {
            it.features.representativeKneeFlexion == bottom
        }.coerceAtLeast(0)

        repFrames.forEachIndexed { index, frame ->
            val flexion = frame.features.representativeKneeFlexion
            frame.phaseProgress = when {
                flexion == null || span <= Geometry.EPSILON -> null
                // 하강·상승을 분리해 각 구간을 0~1로 정규화한다(문서 §15.1).
                index <= bottomIndex -> ((flexion - top) / span).coerceIn(0f, 1f)
                else -> ((flexion - top) / span).coerceIn(0f, 1f)
            }
        }

        val evaluable = repFrames.count { it.features.minConfidence >= EVALUABLE_CONFIDENCE }
            .toFloat() / repFrames.size
        val validRatio = repFrames.count { it.features.representativeKneeFlexion != null }
            .toFloat() / repFrames.size

        val rep = CaptureRep(
            repId = currentRepId,
            counted = counted,
            formEvaluable = evaluable >= MIN_EVALUABLE_RATIO,
            startMs = repFrames.first().timestampMs,
            bottomMs = repFrames.getOrNull(bottomIndex)?.timestampMs,
            endMs = repFrames.last().timestampMs,
            descentDurationMs = repFrames.getOrNull(bottomIndex)?.let {
                it.timestampMs - repFrames.first().timestampMs
            },
            ascentDurationMs = repFrames.getOrNull(bottomIndex)?.let {
                repFrames.last().timestampMs - it.timestampMs
            },
            maxKneeFlexion = repFrames.mapNotNull { it.features.representativeKneeFlexion }.maxOrNull(),
            maxHipFlexion = repFrames.mapNotNull {
                it.features.leftHipFlexion ?: it.features.rightHipFlexion
            }.maxOrNull(),
            maxAnkleDorsiflexion = repFrames.mapNotNull {
                it.features.leftAnkleDorsiflexion ?: it.features.rightAnkleDorsiflexion
            }.maxOrNull(),
            maxDepthRatio = repFrames.mapNotNull { it.features.depthRatio }.maxOrNull(),
            minThighAngle = repFrames.mapNotNull { it.features.thighAngle }.minOrNull(),
            maxTrunkLean = repFrames.mapNotNull { it.features.trunkLean }.maxOrNull(),
            maxHeelRise = repFrames.mapNotNull {
                listOfNotNull(it.features.leftHeelRise, it.features.rightHeelRise).maxOrNull()
            }.maxOrNull(),
            maxLeftMedialKnee = repFrames.mapNotNull {
                it.features.leftMedialKneeDisplacement
            }.maxOrNull(),
            maxRightMedialKnee = repFrames.mapNotNull {
                it.features.rightMedialKneeDisplacement
            }.maxOrNull(),
            maxHipShift = repFrames.mapNotNull { it.features.hipShiftRatio?.let(::abs) }.maxOrNull(),
            validFrameRatio = validRatio,
            frameCount = repFrames.size,
            label = RepLabel.UNREVIEWED,
            includeInReference = false,
            exclusionReason = null,
        )
        completedReps += rep
        return rep
    }

    /** 카메라·모델 전환처럼 시계열이 끊기면 호출한다. */
    fun resetTracking() {
        stateMachine.reset()
        kneeEma.reset()
        kneeMedian.reset()
        repStartIndex = null
    }

    /** 지금까지 기록한 것을 세션 레코드로 만든다. */
    fun build(): CaptureSessionRecord = CaptureSessionRecord(
        info = info,
        calibration = calibration,
        frames = frames.map { it.toImmutable() },
        reps = completedReps.toList(),
    )

    /** 진행도를 나중에 덮어써야 하므로 가변으로 둔다. */
    private class MutableFrame(
        val frameIndex: Int,
        val timestampMs: Long,
        val repId: Int?,
        val phase: CapturePhase,
        val rawKeypoints: List<Keypoint>,
        val filteredKeypoints: List<Keypoint>,
        val features: FrameFeatures,
        var phaseProgress: Float? = null,
    ) {
        fun flexionOrMax() = features.representativeKneeFlexion ?: Float.MAX_VALUE
        fun flexionOrMin() = features.representativeKneeFlexion ?: -Float.MAX_VALUE

        fun toImmutable() = CaptureFrame(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            repId = repId,
            phase = phase,
            phaseProgress = phaseProgress,
            rawKeypoints = rawKeypoints,
            filteredKeypoints = filteredKeypoints,
            features = features,
        )
    }

    private companion object {
        /** 스무딩 창 초기 산출용. 실측 fps로 갱신하지 않는다 — 수집 중 창을 바꾸면 특징이 왜곡된다. */
        const val ASSUMED_FPS = 25.0

        /** 이 신뢰도 이상인 프레임만 자세 평가 가능으로 센다 (문서 §8.1). */
        const val EVALUABLE_CONFIDENCE = 0.5f

        /** rep의 이 비율 이상이 평가 가능해야 `formEvaluable`. */
        const val MIN_EVALUABLE_RATIO = 0.8f
    }
}
