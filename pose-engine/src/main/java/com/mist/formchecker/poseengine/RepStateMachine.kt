package com.mist.formchecker.poseengine

import kotlin.math.abs

/**
 * rep 카운팅 상태.
 *
 * 설계문서 3.2절 4번의 상태 전이를 구현한다. 히스테리시스를 두어 임계값 경계에서 상태가
 * 진동하지 않게 하고, 최소 유지 시간으로 짧은 튐을 걸러낸다.
 */
enum class RepState {
    /** 각도를 얻지 못함(사람 미인식·저신뢰). */
    IDLE,

    /** 선 자세. rep 시작을 기다린다. */
    STANDING,

    /** 하강 중. */
    DESCENDING,

    /** rep으로 인정되는 최저 구간에 진입함. */
    BOTTOM,

    /** 최저점을 지나 올라오는 중. */
    ASCENDING,
}

/** 상태머신이 내보내는 사건. */
sealed interface RepEvent {
    /** 하강이 확정되어 rep이 시작됨. */
    data class Started(val repIndex: Int, val timestampMs: Long) : RepEvent

    /**
     * rep 완주. [summary]에 이 rep의 요약이 담긴다.
     *
     * **깊이가 충분했는지는 여기서 판단하지 않는다.** 카운팅과 자세 판정을 분리하기 위해,
     * 깊이 유효성은 호출부가 [RepSummary]의 최저 각도를 [FormThresholds]로 따로 평가한다.
     */
    data class Completed(val summary: RepSummary) : RepEvent

    /** rep이 중단됨. */
    data class Aborted(val repIndex: Int, val reason: Reason, val timestampMs: Long) : RepEvent {
        enum class Reason {
            /** 최저점에 도달하지 못한 채 선 자세로 복귀 (얕은 흔들림). */
            NOT_DEEP_ENOUGH,

            /** [CountingThresholds.minRepDurationMs] 미달. 반동으로 판단. */
            TOO_FAST,

            /** [CountingThresholds.maxRepDurationMs] 초과. 중간에 멈춰 선 경우. */
            TIMEOUT,

            /** 각도를 오래 얻지 못함 (가림·프레임 이탈). */
            POSE_LOST,
        }
    }
}

/**
 * 한 프레임의 입력.
 *
 * 상태머신은 키포인트를 직접 다루지 않는다. 각도 계산과 스무딩은 상위 계층이 하고, 여기는
 * **이미 스무딩된 각도**를 받는다. 그래야 상태머신을 합성 시계열로 단위 테스트할 수 있다.
 */
data class RepFrame(
    val timestampMs: Long,
    /**
     * 이 프레임에서 **실제로 측정된** 무릎 각도. null이면 측정 실패(가림·저신뢰).
     *
     * [smoothedKneeAngle]만으로는 측정 실패를 알 수 없다. [AngleEma]가 저신뢰 프레임에서
     * 직전 값을 유지하도록 설계돼 있어(가림 한두 프레임으로 rep이 끊기지 않게 하기 위함),
     * 스무딩된 값은 측정이 없어도 계속 나온다. 유효 프레임 비율 집계와 가림 판정에는
     * 이 원본 값이 필요하다.
     */
    val rawKneeAngle: Float?,
    /** 상태 전이 판단용. [AngleEma]를 통과한 값. 측정 실패 프레임에서는 직전 값이 유지된다. */
    val smoothedKneeAngle: Float?,
    /** 특징 집계용. [MedianWindow]를 통과한 값. 최저점 탐색과 min/max 집계에 쓴다. */
    val medianKneeAngle: Float?,
    val leftKneeAngle: Float?,
    val rightKneeAngle: Float?,
    val hipAngle: Float?,
    val torsoLeanDegrees: Float?,
    val kneeAsymmetryDegrees: Float?,
    val leftFoot: FootSample?,
    val rightFoot: FootSample?,
    val minLegConfidence: Float,
)

/**
 * 무릎 각도 시계열로 rep을 센다.
 *
 * ## 상태머신이 아는 것과 모르는 것
 * 이 클래스는 [CountingThresholds]만 안다. "깊이가 충분한가"는 판단하지 않는다. 설계문서
 * 3.2절이 BOTTOM 진입을 `< 100°`, 깊이 부족을 `≥ 120°`로 정의해 두 조건이 동시에 참일 수
 * 없는 모순이 있었는데, 카운팅과 자세 판정을 타입 수준에서 분리해 그런 모순이 구조적으로
 * 재발하지 않게 한다.
 *
 * ## 두 종류의 각도를 받는 이유
 * 상태 전이는 EMA(안정적, 지연 허용), 최저점 탐색은 median(지연 없음)을 쓴다. EMA로 최저점을
 * 찾으면 실측 기준 약 99ms 뒤로 밀려 이미 올라오는 중의 발 좌표를 수집하게 된다
 * ([AngleSmoothing] 참고).
 *
 * 스레드 안전하지 않다. 단일 분석 스레드에서만 호출할 것.
 */
class RepStateMachine(
    private val thresholds: CountingThresholds = CountingThresholds(),
    private val cameraAngle: () -> CameraAngle = { CameraAngle.SIDE },
) {
    var state: RepState = RepState.IDLE
        private set

    var completedReps: Int = 0
        private set

    private var repIndex = 0

    // ── 상태 전이 확정용 ─────────────────────────────────────
    private var pendingState: RepState? = null
    private var pendingSinceMs: Long = 0L
    private var lastMissingStartMs: Long? = null

    // ── 진행 중인 rep 누적 ───────────────────────────────────
    private var repStartMs: Long = 0L
    private var frameCountInRep = 0
    private var validFrameCountInRep = 0
    private var minKnee: Float? = null
    private var maxKnee: Float? = null
    private var minHip: Float? = null
    private var maxHip: Float? = null
    private var maxTorsoLean: Float? = null
    private var maxAngularVelocity: Float? = null
    private var previousAngle: Float? = null
    private var previousAngleMs: Long? = null
    private var reachedBottom = false

    private var topPosture: RepPosture? = null
    private var bottomPosture: RepPosture? = null
    private var bottomMedianAngle: Float? = null

    /**
     * 한 프레임을 처리한다.
     *
     * @return 이 프레임에서 발생한 사건. 없으면 null.
     */
    fun update(frame: RepFrame): RepEvent? {
        // 측정 실패 프레임에서는 상태를 전이하지 않는다. 스무딩된 값은 직전 값이 유지된
        // 것이므로, 그 값으로 전이하면 실제로 일어나지 않은 동작이 rep으로 기록된다.
        if (frame.rawKneeAngle == null) {
            return handleMissing(frame.timestampMs)
        }
        lastMissingStartMs = null

        val angle = frame.smoothedKneeAngle ?: return null

        if (state != RepState.IDLE && state != RepState.STANDING) {
            accumulate(frame)
            timeoutCheck(frame.timestampMs)?.let { return it }
        }

        return when (state) {
            RepState.IDLE -> {
                // 각도를 얻었으니 최소한 관찰은 시작한다. 선 자세가 확정될 때까지 대기.
                if (settled(RepState.STANDING, angle >= thresholds.standingEnterAngle, frame.timestampMs)) {
                    state = RepState.STANDING
                    captureTop(frame)
                }
                null
            }

            RepState.STANDING -> {
                // 선 자세를 유지하는 동안 top 기준값을 갱신한다. 가장 곧게 선 시점이
                // delta_top 계열 특징의 기준이 되어야 한다.
                if (angle > (topPosture?.kneeAngle ?: Float.MIN_VALUE)) captureTop(frame)

                if (settled(RepState.DESCENDING, angle < thresholds.standingExitAngle, frame.timestampMs)) {
                    startRep(frame)
                    RepEvent.Started(repIndex, frame.timestampMs)
                } else {
                    null
                }
            }

            RepState.DESCENDING -> {
                updateBottomCandidate(frame)
                when {
                    settled(RepState.BOTTOM, angle < thresholds.repBottomAngle, frame.timestampMs) -> {
                        state = RepState.BOTTOM
                        reachedBottom = true
                        null
                    }
                    // 최저점에 못 가고 다시 서면 rep 시도를 취소한다.
                    settled(RepState.STANDING, angle >= thresholds.standingEnterAngle, frame.timestampMs) ->
                        abort(RepEvent.Aborted.Reason.NOT_DEEP_ENOUGH, frame.timestampMs)
                    else -> null
                }
            }

            RepState.BOTTOM -> {
                updateBottomCandidate(frame)
                if (settled(RepState.ASCENDING, angle >= thresholds.repBottomAngle, frame.timestampMs)) {
                    state = RepState.ASCENDING
                }
                null
            }

            RepState.ASCENDING -> {
                if (settled(RepState.STANDING, angle >= thresholds.standingEnterAngle, frame.timestampMs)) {
                    completeRep(frame)
                } else {
                    // 다시 내려가면 하강으로 되돌린다. 최저점 후보는 유지하므로 더 깊은
                    // 지점이 나오면 갱신된다.
                    if (settled(RepState.BOTTOM, angle < thresholds.repBottomAngle, frame.timestampMs)) {
                        state = RepState.BOTTOM
                    }
                    null
                }
            }
        }
    }

    /** 카메라 전환·모델 교체처럼 시계열이 끊기는 경우 호출한다. */
    fun reset() {
        state = RepState.IDLE
        pendingState = null
        lastMissingStartMs = null
        clearRep()
        topPosture = null
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 조건이 [CountingThresholds.minHoldMs] 이상 연속으로 참일 때만 전이를 허용한다.
     *
     * 한 프레임 튐으로 상태가 바뀌면 rep이 중복 카운트된다. 스무딩만으로는 부족해서
     * 시간 조건을 함께 둔다.
     */
    private fun settled(target: RepState, condition: Boolean, nowMs: Long): Boolean {
        if (!condition) {
            if (pendingState == target) pendingState = null
            return false
        }
        if (pendingState != target) {
            pendingState = target
            pendingSinceMs = nowMs
            // 유지 시간이 0이면 즉시 전이한다.
            return thresholds.minHoldMs <= 0L
        }
        return nowMs - pendingSinceMs >= thresholds.minHoldMs
    }

    private fun handleMissing(nowMs: Long): RepEvent? {
        val since = lastMissingStartMs ?: nowMs.also { lastMissingStartMs = it }
        if (state == RepState.IDLE || state == RepState.STANDING) {
            if (nowMs - since >= thresholds.maxMissingMs) state = RepState.IDLE
            return null
        }
        if (nowMs - since >= thresholds.maxMissingMs) {
            return abort(RepEvent.Aborted.Reason.POSE_LOST, nowMs)
        }
        // 짧은 가림은 상태를 유지한다. 프레임 수만 세어 validFrameRatio에 반영한다.
        frameCountInRep++
        return null
    }

    private fun timeoutCheck(nowMs: Long): RepEvent? =
        if (nowMs - repStartMs > thresholds.maxRepDurationMs) {
            abort(RepEvent.Aborted.Reason.TIMEOUT, nowMs)
        } else {
            null
        }

    private fun startRep(frame: RepFrame) {
        repIndex++
        state = RepState.DESCENDING
        repStartMs = frame.timestampMs
        clearRepAccumulators()
        accumulate(frame)
    }

    /**
     * 특징을 누적한다. **median 값을 쓴다.**
     *
     * EMA 값으로 최저 각도를 집계하면 실측 기준 약 0.8도 얕게 나오고, raw 값을 쓰면 한
     * 프레임 튐이 그대로 최저값이 된다. median은 스파이크를 제거하면서 지연이 없어 둘 다
     * 피한다 ([AngleSmoothing] 참고).
     */
    private fun accumulate(frame: RepFrame) {
        frameCountInRep++
        validFrameCountInRep++

        val angle = frame.medianKneeAngle ?: frame.smoothedKneeAngle ?: return
        minKnee = minOfNullable(minKnee, angle)
        maxKnee = maxOfNullable(maxKnee, angle)
        frame.hipAngle?.let {
            minHip = minOfNullable(minHip, it)
            maxHip = maxOfNullable(maxHip, it)
        }
        frame.torsoLeanDegrees?.let { maxTorsoLean = maxOfNullable(maxTorsoLean, it) }

        // 각속도는 deg/s로 환산한다. 프레임당 변화량으로 두면 fps에 종속된다.
        val prev = previousAngle
        val prevMs = previousAngleMs
        if (prev != null && prevMs != null) {
            val dtSec = (frame.timestampMs - prevMs) / 1000.0
            if (dtSec > 0) {
                val velocity = (abs(angle - prev) / dtSec).toFloat()
                maxAngularVelocity = maxOfNullable(maxAngularVelocity, velocity)
            }
        }
        previousAngle = angle
        previousAngleMs = frame.timestampMs
    }

    /** median 각도가 지금까지의 최저치면 이 프레임을 최저점 후보로 삼는다. */
    private fun updateBottomCandidate(frame: RepFrame) {
        val median = frame.medianKneeAngle ?: return
        val best = bottomMedianAngle
        if (best == null || median < best) {
            bottomMedianAngle = median
            bottomPosture = frame.toPosture(frameCountInRep)
        }
    }

    private fun captureTop(frame: RepFrame) {
        topPosture = frame.toPosture(frameIndex = 0)
    }

    private fun completeRep(frame: RepFrame): RepEvent {
        val duration = frame.timestampMs - repStartMs
        if (!reachedBottom) {
            return abort(RepEvent.Aborted.Reason.NOT_DEEP_ENOUGH, frame.timestampMs)
        }
        if (duration < thresholds.minRepDurationMs) {
            return abort(RepEvent.Aborted.Reason.TOO_FAST, frame.timestampMs)
        }

        val bottom = bottomPosture
        val summary = RepSummary(
            repIndex = repIndex,
            startTimeMs = repStartMs,
            endTimeMs = frame.timestampMs,
            aggregate = RepAggregate(
                minKneeAngle = minKnee,
                maxKneeAngle = maxKnee,
                minHipAngle = minHip,
                maxHipAngle = maxHip,
                maxTorsoLeanDegrees = maxTorsoLean,
                descentDurationMs = bottom?.let { it.timestampMs - repStartMs },
                ascentDurationMs = bottom?.let { frame.timestampMs - it.timestampMs },
                maxAngularVelocityDegPerSec = maxAngularVelocity,
                validFrameRatio = if (frameCountInRep > 0) {
                    validFrameCountInRep.toFloat() / frameCountInRep
                } else {
                    0f
                },
                analyzedFrameCount = frameCountInRep,
            ),
            top = topPosture,
            bottom = bottom,
            cameraAngle = cameraAngle(),
        )

        state = RepState.STANDING
        completedReps++
        clearRep()
        captureTop(frame)
        return RepEvent.Completed(summary)
    }

    private fun abort(reason: RepEvent.Aborted.Reason, nowMs: Long): RepEvent {
        val index = repIndex
        state = if (reason == RepEvent.Aborted.Reason.POSE_LOST) RepState.IDLE else RepState.STANDING
        clearRep()
        return RepEvent.Aborted(index, reason, nowMs)
    }

    private fun clearRep() {
        clearRepAccumulators()
        bottomPosture = null
        bottomMedianAngle = null
        pendingState = null
    }

    private fun clearRepAccumulators() {
        frameCountInRep = 0
        validFrameCountInRep = 0
        minKnee = null
        maxKnee = null
        minHip = null
        maxHip = null
        maxTorsoLean = null
        maxAngularVelocity = null
        previousAngle = null
        previousAngleMs = null
        reachedBottom = false
    }

    private fun RepFrame.toPosture(frameIndex: Int) = RepPosture(
        frameIndex = frameIndex,
        timestampMs = timestampMs,
        // 특징 축약에는 median 값을 쓴다. 단일 프레임 노이즈를 피하기 위함이다.
        kneeAngle = medianKneeAngle ?: smoothedKneeAngle,
        leftKneeAngle = leftKneeAngle,
        rightKneeAngle = rightKneeAngle,
        hipAngle = hipAngle,
        torsoLeanDegrees = torsoLeanDegrees,
        kneeAsymmetryDegrees = kneeAsymmetryDegrees,
        leftFoot = leftFoot,
        rightFoot = rightFoot,
        minLegConfidence = minLegConfidence,
        framesUsed = 1,
    )

    private fun minOfNullable(current: Float?, value: Float) =
        if (current == null || value < current) value else current

    private fun maxOfNullable(current: Float?, value: Float) =
        if (current == null || value > current) value else current
}
