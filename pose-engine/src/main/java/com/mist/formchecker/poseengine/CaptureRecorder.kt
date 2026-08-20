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
    /**
     * 아직 내보내지 않은 첫 프레임의 인덱스.
     *
     * rep 경계와 **별개로** 관리한다. rep 창은 "어디부터가 이 rep인가"이고 이 값은
     * "어디까지 파일에 나갔나"다. 둘을 하나로 쓰면 rep 밖 프레임이 영원히 안 나간다.
     */
    private var exportedUpTo = 0
    private var standingFlexion: Float? = null

    val recordedFrames: Int get() = frames.size
    val recordedReps: Int get() = completedReps.size

    /**
     * rep 하나가 끝났을 때의 결과.
     *
     * ## 왜 프레임을 rep 단위로 내보내는가
     * 진행도는 **rep이 끝나야 계산된다** — 하강 중에는 최저점을 모른다. 프레임을 즉시
     * 기록하면 진행도가 항상 비어 나가고, 그건 임계값 생성에 필요한 핵심 컬럼이다.
     * (초기 구현이 실제로 그렇게 새어 실기에서 2243행 전부 빈 칸으로 나왔다.)
     *
     * @property frames 이 배치로 나가는 프레임 전체. **rep 프레임만이 아니다** — 직전
     *   배치 이후 쌓인 rep 밖 프레임(선 자세 대기 등)까지 함께 실린다. rep 밖 프레임을
     *   버리면 하강 시작 앞뒤가 파일에서 사라지고, 그건 재계산으로 복구할 수 없다.
     *   이 배치 방식이 순서를 지켜 주므로 즉시 기록으로 돌아갈 필요가 없다.
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
            repStartIndex = claimDescentStart(features.representativeKneeFlexion, timestampMs)
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
        //
        // 내보내는 범위는 rep 창이 아니라 **아직 안 나간 전부**다. rep 밖 프레임도 여기
        // 실려 나간다.
        return CompletedRep(rep, drain())
    }

    /**
     * 아직 내보내지 않은 프레임을 모두 낸다. 세션을 닫을 때 호출할 것.
     *
     * 마지막 rep 이후의 대기 프레임과, 중단된 채 끝난 rep의 프레임이 여기서 나간다.
     * 호출하지 않으면 그만큼이 파일에 없다.
     */
    fun drain(): List<CaptureFrame> {
        if (exportedUpTo >= frames.size) return emptyList()
        val pending = frames.subList(exportedUpTo, frames.size).map { it.toImmutable() }
        exportedUpTo = frames.size
        return pending
    }

    /**
     * rep 창을 실제 하강 시작으로 되돌린다.
     *
     * ## 왜 필요한가
     * 상태머신은 **카운팅용**이라 rep을 늦게 연다. 임계값(`standingExitAngle` 내각 145°
     * = 굴곡 35°)에 유지 시간 100ms와 스무딩 지연(EMA 120ms + median 133ms)이 겹치고,
     * 하강 속도가 약 0.16°/ms이므로 20° 남짓이 더 밀린다. 실측 20 rep에서 rep 첫 프레임의
     * 굴곡 중앙값이 **55.8°**(범위 48.9~68.4)였다 — 하강의 절반이 창 밖이었다.
     *
     * 결과로 `descent_duration_ms` 중앙값이 287ms인데 `ascent_duration_ms`는 681ms였다.
     * 맨몸 스쿼트 하강이 상승보다 2.4배 빠를 수는 없다. 템포·진행도·단계 라벨이 전부
     * 그만큼 틀어진다.
     *
     * ## 왜 임계값을 낮추지 않는가
     * 카운팅 임계값은 이미 검증된 값이고, 낮춰도 스무딩 지연은 그대로 남는다. 대신
     * **기록 창만** 뒤로 넓힌다 — 카운팅 임계값과 자세 판정 기준을 분리한 원칙을 rep
     * 경계에도 적용하는 것이다.
     *
     * 뒤로 걸어가며 굴곡이 계속 줄어드는(=시간순으로는 내려가는 중인) 구간을 rep에
     * 편입한다. 굴곡이 다시 늘어나면 그 앞은 다른 동작이므로 멈춘다.
     *
     * @param currentFlexion 지금 프레임의 굴곡. 하강 중 가장 깊은 값이다.
     * @return 되돌린 rep 시작 인덱스. 되돌릴 게 없으면 지금 프레임의 인덱스.
     */
    private fun claimDescentStart(currentFlexion: Float?, nowMs: Long): Int {
        // 지금 프레임은 아직 frames에 없다. 그 자리가 rep의 기본 시작점이다.
        val startedAt = frames.size
        var previous = currentFlexion ?: return startedAt
        var index = startedAt
        while (index > exportedUpTo) {
            val candidate = frames[index - 1]
            // 이미 내보낸 프레임은 되돌릴 수 없다. rep_id를 바꿔도 파일에는 안 반영된다.
            // 앞선 rep의 프레임도 가져오지 않는다.
            if (candidate.repId != null) break
            val flexion = candidate.features.representativeKneeFlexion ?: break
            // 뒤로 갈수록 굴곡이 줄어야 한다. 늘어나면 하강 시작을 이미 지났다.
            if (flexion > previous + MONOTONE_TOLERANCE_DEGREES) break
            if (nowMs - candidate.timestampMs > MAX_DESCENT_LOOKBACK_MS) break
            index--
            previous = flexion
            candidate.repId = currentRepId
            // 선 자세까지 닿았으면 거기가 하강 시작이다. 더 가면 직전 rep의 기립 구간이다.
            val standing = standingFlexion
            if (standing != null && flexion <= standing + STANDING_TOLERANCE_DEGREES) break
        }
        return index
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
        /**
         * 소속 rep. rep이 시작될 때 앞선 대기 프레임을 편입하므로 가변이다
         * ([claimDescentStart] 참고).
         */
        var repId: Int?,
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

        /**
         * 하강 시작을 되돌릴 때 허용하는 굴곡 증가폭(도).
         *
         * 0으로 두면 좌표 지터 한 번에 걸음이 멈춘다. `PoseAnalyzer`가 아직 필터를
         * 적용하지 않아 특징이 원본 좌표에서 나오므로 여유가 필요하다.
         */
        const val MONOTONE_TOLERANCE_DEGREES = 3f

        /** 선 자세로 볼 굴곡 여유(도). 여기 닿으면 하강 시작으로 본다. */
        const val STANDING_TOLERANCE_DEGREES = 5f

        /**
         * 되돌릴 수 있는 최대 시간(ms).
         *
         * 굴곡이 아주 천천히 늘기만 해도 걸음이 계속되는 것을 막는다 — 실측 하강이
         * 300ms 남짓이므로 1.5초는 정상 하강을 자르지 않으면서 폭주는 막는 값이다.
         */
        const val MAX_DESCENT_LOOKBACK_MS = 1_500L

        /** 스무딩 창 초기 산출용. 실측 fps로 갱신하지 않는다 — 수집 중 창을 바꾸면 특징이 왜곡된다. */
        const val ASSUMED_FPS = 25.0

        /** 이 신뢰도 이상인 프레임만 자세 평가 가능으로 센다 (문서 §8.1). */
        const val EVALUABLE_CONFIDENCE = 0.5f

        /** rep의 이 비율 이상이 평가 가능해야 `formEvaluable`. */
        const val MIN_EVALUABLE_RATIO = 0.8f
    }
}
