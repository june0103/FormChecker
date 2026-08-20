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
    /** 세션 촬영 의도. 모든 rep에 그대로 붙는다. */
    private val intent: CaptureIntent,
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
     * rep 하나가 끝났을 때의 결과. 요약과 그 rep의 프레임을 함께 낸다.
     *
     * ## 왜 프레임을 rep 단위로 내보내는가
     * 진행도는 **rep이 끝나야 계산된다** — 하강 중에는 최저점을 모른다. 프레임을 즉시
     * 기록하면 진행도가 항상 비어 나가고, 그건 임계값 생성에 필요한 핵심 컬럼이다.
     * (초기 구현이 실제로 그렇게 새어 실기에서 2243행 전부 빈 칸으로 나왔다.)
     */
    data class CompletedRep(val rep: CaptureRep, val frames: List<CaptureFrame>)

    /**
     * 프레임 하나를 받는다.
     *
     * @param rawPose 필터 전 좌표. 재계산을 위해 그대로 보관한다.
     * @param filteredPose 필터 후 좌표. 특징 계산에 쓴다.
     * @return 이 프레임에서 rep이 마감됐으면 요약과 프레임들. 없으면 null.
     */
    fun accept(
        rawPose: Pose,
        filteredPose: Pose,
        timestampMs: Long,
    ): CompletedRep? {
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
    private fun finishRep(summary: RepSummary?, counted: Boolean): CompletedRep? {
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

        // 하강·상승을 분리해 각 구간을 0~1로 정규화한다(문서 §15.1). 두 구간의 진행도가
        // 같은 스케일이어야 "하강 70~80%"와 "상승 70~80%"를 각각 비교할 수 있다.
        // 상승은 굴곡이 줄어드는 방향이라 부호를 뒤집는다 — 뒤집지 않으면 상승 초반이
        // 진행도 1.0으로 나와 하강 끝과 구분되지 않는다.
        repFrames.forEachIndexed { index, frame ->
            val flexion = frame.features.representativeKneeFlexion
            val progress = when {
                flexion == null || span <= Geometry.EPSILON -> null
                index <= bottomIndex -> ((flexion - top) / span).coerceIn(0f, 1f)
                else -> ((bottom - flexion) / span).coerceIn(0f, 1f)
            }
            frame.phaseProgress = progress

            // 단계 라벨도 실제 최저점 기준으로 다시 붙인다.
            //
            // 상태머신의 BOTTOM 임계값은 **카운팅용**이라 실제 최저점보다 훨씬 얕은
            // 지점에서 진입한다(내각 130° = 굴곡 50°인데 실측 최저 굴곡은 110°).
            // 그대로 두면 실기 499프레임 중 381이 BOTTOM으로 찍혀 단계별 임계값이
            // 무의미해진다. 카운팅 임계값과 자세 판정 기준을 분리한 원칙을 단계
            // 라벨에도 적용한다.
            //
            // 최저점 판정은 **구간마다 방향이 반대다.** 진행도가 하강에서는 1에 가까울수록,
            // 상승에서는 0에 가까울수록 최저점이다(위에서 부호를 뒤집었으므로). 방향을
            // 무시하고 `progress >= BOTTOM_PROGRESS` 하나로 보면 상승 **끝**, 즉 다 일어선
            // 프레임이 BOTTOM으로 찍힌다 — 실측 20 rep에서 BOTTOM 50개 중 8개가 그랬다.
            frame.phase = when {
                progress == null -> frame.phase
                index <= bottomIndex ->
                    if (progress >= BOTTOM_PROGRESS) {
                        CapturePhase.BOTTOM
                    } else {
                        CapturePhase.DESCENDING
                    }
                else ->
                    if (progress <= 1f - BOTTOM_PROGRESS) {
                        CapturePhase.BOTTOM
                    } else {
                        CapturePhase.ASCENDING
                    }
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
            intent = intent,
        )
        completedReps += rep
        // 진행도를 덮어쓴 뒤에 불변 스냅샷을 만든다. 이 시점 이전에 내보내면 진행도가
        // 빈 채로 기록된다.
        return CompletedRep(rep, repFrames.map { it.toImmutable() })
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
        /**
         * 단계 라벨. rep이 끝날 때 실제 최저점 기준으로 다시 붙이므로 가변이다.
         *
         * 상태머신이 붙인 값은 카운팅 임계값 기준이라 실제 최저점보다 훨씬 얕은 지점에서
         * BOTTOM으로 넘어간다.
         */
        var phase: CapturePhase,
        val rawKeypoints: List<Keypoint>,
        val filteredKeypoints: List<Keypoint>,
        val features: FrameFeatures,
        /** 진행도도 rep이 끝나야 계산된다. */
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
        /**
         * 최저점 구간의 폭. 하강은 진행도가 이 값 이상, 상승은 `1 - 이 값` 이하일 때
         * 최저점으로 본다 — 두 구간의 진행도 방향이 반대이기 때문이다.
         */
        const val BOTTOM_PROGRESS = 0.95f

        /** 스무딩 창 초기 산출용. 실측 fps로 갱신하지 않는다 — 수집 중 창을 바꾸면 특징이 왜곡된다. */
        const val ASSUMED_FPS = 25.0

        /** 이 신뢰도 이상인 프레임만 자세 평가 가능으로 센다 (문서 §8.1). */
        const val EVALUABLE_CONFIDENCE = 0.5f

        /** rep의 이 비율 이상이 평가 가능해야 `formEvaluable`. */
        const val MIN_EVALUABLE_RATIO = 0.8f
    }
}
