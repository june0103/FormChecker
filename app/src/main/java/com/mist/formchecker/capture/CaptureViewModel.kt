package com.mist.formchecker.capture

import android.app.Application
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.poseengine.CaptureQuality
import com.mist.formchecker.poseengine.CaptureRecorder
import com.mist.formchecker.poseengine.CaptureRep
import com.mist.formchecker.poseengine.CaptureSessionInfo
import com.mist.formchecker.poseengine.CaptureSessionRecord
import com.mist.formchecker.poseengine.CaptureView
import com.mist.formchecker.poseengine.MedianWindow
import com.mist.formchecker.poseengine.Pose
import com.mist.formchecker.poseengine.PoseEngineHandle
import com.mist.formchecker.poseengine.RepLabel
import com.mist.formchecker.poseengine.RtmPoseEngine
import com.mist.formchecker.poseengine.StandingCalibration
import com.mist.formchecker.ui.screen.workout.PoseAnalyzer
import com.mist.formchecker.ui.screen.workout.PoseResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 수집 모드의 단계. 화면 흐름이 이 순서로 강제된다. */
enum class CaptureStage {
    /** 세션 정보 입력. */
    SETUP,

    /** 카메라 위치·관절 가시성 검사. 통과해야 다음으로 간다. */
    QUALITY_CHECK,

    /** 3초 기립 자세 기록. */
    CALIBRATING,

    /** 스쿼트 반복 측정. */
    RECORDING,

    /** rep별 라벨링. */
    REVIEW,
}

data class CaptureUiState(
    val stage: CaptureStage = CaptureStage.SETUP,
    // ── 세션 정보 ───────────────────────────────────────────
    val personId: String = "P001",
    val view: CaptureView = CaptureView.SIDE_LEFT,
    val footwear: String = "",
    val cameraHeightCm: String = "",
    val cameraDistanceCm: String = "",
    // ── 엔진 ────────────────────────────────────────────────
    val engineReady: Boolean = false,
    val engineError: String? = null,
    val modelName: String = "",
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val result: PoseResult? = null,
    val analysisFps: Double = 0.0,
    // ── 품질 ────────────────────────────────────────────────
    val quality: CaptureQuality? = null,
    // ── 캘리브레이션 ────────────────────────────────────────
    /** 남은 유지 시간(ms). 0이 되면 판정한다. */
    val calibrationRemainingMs: Long = 0,
    val calibration: StandingCalibration? = null,
    val calibrationProblems: List<StandingCalibration.Problem> = emptyList(),
    // ── 기록 ────────────────────────────────────────────────
    val recordedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val reps: List<CaptureRep> = emptyList(),
) {
    val isFrontCamera: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /** 카메라가 살아 있어야 하는 단계. */
    val needsCamera: Boolean get() = stage != CaptureStage.SETUP && stage != CaptureStage.REVIEW

    /** 기준 표본으로 승인된 rep 수. */
    val approvedReps: Int get() = reps.count { it.includeInReference }
}

/**
 * 기준 자세 데이터 수집 모드 (문서 §4).
 *
 * ## 왜 운동 모드와 화면을 분리했는가
 * 1. **라벨링 시점이 다르다.** 촬영 전에 "정상으로 찍을 것"이라고 선언해도 실제로 정상이었는지는
 *    찍고 봐야 안다. 검토 단계가 흐름에 있어야 한다(문서 §14.2).
 * 2. **캘리브레이션이 절차를 강제해야 한다.** 실패하면 진행을 막아야 하는데, 운동 화면에
 *    끼워 넣을 수 없는 흐름이다(문서 §6.2).
 * 3. **운동 화면은 제품이다.** 수집용 컨트롤이 제품 화면에 남으면 안 된다.
 *
 * ## 분석 파이프라인은 공유한다
 * 카메라·RTMPose·상태머신·스무딩은 운동 모드와 같은 것을 쓴다(문서 §2.2). 여기서 더하는
 * 것은 단계 흐름, 품질 게이트, 기록, 라벨링뿐이다.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var engine: PoseEngineHandle? = null
    private val logSession = CaptureLogSession(application, viewModelScope)

    private var recorder: CaptureRecorder? = null
    private var sessionInfo: CaptureSessionInfo? = null

    /** 캘리브레이션 구간에 모으는 프레임. (pose, 종횡비). */
    private val calibrationFrames = mutableListOf<Pair<Pose, Float>>()
    private var calibrationStartMs: Long? = null

    /** 마지막으로 소리로 알린 품질 상태. 같은 상태에서 반복해 울리지 않게 한다. */
    private var lastQualityPassed: Boolean? = null

    private val cues = CaptureCues()

    // 실측 fps
    private var smoothedFrameIntervalMs = 0.0
    private var lastFrameTimestampMs: Long? = null
    private var fpsWindow = MedianWindow(5)

    init {
        loadEngine()
        viewModelScope.launch {
            logSession.writtenFrames.collect { count ->
                _uiState.update { it.copy(recordedFrames = count) }
            }
        }
        viewModelScope.launch {
            logSession.droppedFrames.collect { count ->
                _uiState.update { it.copy(droppedFrames = count) }
            }
        }
    }

    // ── 세션 설정 ───────────────────────────────────────────

    fun updatePersonId(value: String) = _uiState.update { it.copy(personId = value) }
    fun updateFootwear(value: String) = _uiState.update { it.copy(footwear = value) }
    fun updateCameraHeight(value: String) = _uiState.update { it.copy(cameraHeightCm = value) }
    fun updateCameraDistance(value: String) = _uiState.update { it.copy(cameraDistanceCm = value) }

    fun selectView(view: CaptureView) {
        // 촬영 각도가 바뀌면 활성 다리와 판정 항목이 통째로 갈린다. 캘리브레이션도
        // 다시 해야 한다 — 기준선은 각도에 따라 다른 관절로 계산된다.
        _uiState.update {
            it.copy(view = view, calibration = null, quality = null)
        }
    }

    /** 설정을 마치고 품질 검사로 넘어간다. */
    fun beginQualityCheck() {
        _uiState.update { it.copy(stage = CaptureStage.QUALITY_CHECK) }
        lastQualityPassed = null
    }

    // ── 캘리브레이션 ────────────────────────────────────────

    /**
     * 기립 캘리브레이션을 시작한다. 품질 검사를 통과한 상태에서만 호출된다.
     *
     * 정적 자세이므로 여러 프레임을 평균할 수 있다 — 이것이 동작 중 판정과 결정적으로
     * 다른 점이고, 기준선의 노이즈를 원하는 만큼 줄일 수 있는 이유다.
     */
    fun beginCalibration() {
        calibrationFrames.clear()
        calibrationStartMs = null
        _uiState.update {
            it.copy(
                stage = CaptureStage.CALIBRATING,
                calibration = null,
                calibrationProblems = emptyList(),
                calibrationRemainingMs = CALIBRATION_DURATION_MS,
            )
        }
        cues.play(CaptureCues.Cue.QUALITY_OK)
    }

    /** 캘리브레이션을 다시 시도한다. */
    fun retryCalibration() = beginCalibration()

    // ── 기록 ────────────────────────────────────────────────

    /** 캘리브레이션 결과로 세션을 열고 촬영을 시작한다. */
    private fun beginRecording(calibration: StandingCalibration, result: PoseResult) {
        val state = _uiState.value
        val sessionUuid = UUID.randomUUID().toString()
        val info = CaptureSessionInfo(
            personId = state.personId.ifBlank { "UNKNOWN" },
            sessionId = sessionUuid,
            // 같은 사람의 같은 날 정면·측면 세션을 묶는다.
            measurementGroupId = "${state.personId}_${dayStamp()}",
            view = state.view,
            footwear = state.footwear,
            cameraHeightCm = state.cameraHeightCm.toIntOrNull(),
            cameraDistanceCm = state.cameraDistanceCm.toIntOrNull(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            poseModelName = state.modelName,
            frameWidth = result.frameWidth,
            frameHeight = result.frameHeight,
            analysisFps = state.analysisFps,
            capturedAt = isoNow(),
        )
        sessionInfo = info
        recorder = CaptureRecorder(info, calibration)

        logSession.open(
            CaptureSessionRecord(
                info = info,
                calibration = calibration,
                frames = emptyList(),
                reps = emptyList(),
            ),
        )
        _uiState.update {
            it.copy(stage = CaptureStage.RECORDING, calibration = calibration, reps = emptyList())
        }
        cues.play(CaptureCues.Cue.CALIBRATED)
    }

    /** 촬영을 끝내고 검토 단계로 간다. */
    fun finishRecording() {
        _uiState.update { it.copy(stage = CaptureStage.REVIEW) }
        cues.play(CaptureCues.Cue.SESSION_END)
        logSession.logReps(_uiState.value.reps)
    }

    // ── 검토·라벨링 ─────────────────────────────────────────

    /**
     * rep 라벨을 확정한다.
     *
     * 자동 제외 조건(유효 프레임 비율, 미완주, 신뢰도)은 [CaptureRep.withLabel]이 함께
     * 적용한다 — 검토자가 `GOOD`을 줘도 데이터 품질이 미달이면 기준 표본에 넣지 않는다.
     */
    fun labelRep(repId: Int, label: RepLabel) {
        _uiState.update { state ->
            state.copy(
                reps = state.reps.map { if (it.repId == repId) it.withLabel(label) else it },
            )
        }
        logSession.logReps(_uiState.value.reps)
    }

    /** 검토를 마치고 세션을 닫는다. */
    fun finishSession() {
        logSession.logReps(_uiState.value.reps)
        logSession.finish()
        recorder = null
        sessionInfo = null
        _uiState.update {
            CaptureUiState(
                stage = CaptureStage.SETUP,
                personId = it.personId,
                view = it.view,
                footwear = it.footwear,
                cameraHeightCm = it.cameraHeightCm,
                cameraDistanceCm = it.cameraDistanceCm,
                engineReady = it.engineReady,
                modelName = it.modelName,
                lensFacing = it.lensFacing,
            )
        }
    }

    // ── 프레임 처리 ─────────────────────────────────────────

    fun createAnalyzer(): PoseAnalyzer? {
        val ready = engine ?: return null
        return PoseAnalyzer(engine = ready, onResult = ::onResult, onFrameDropped = {})
    }

    private fun onResult(result: PoseResult) {
        val state = _uiState.value
        val fps = trackFps(result.timestampMs)
        val quality = CaptureQuality.of(result.pose, result.aspectRatio, state.view)

        _uiState.update { it.copy(result = result, analysisFps = fps, quality = quality) }

        when (state.stage) {
            CaptureStage.QUALITY_CHECK -> announceQuality(quality)
            CaptureStage.CALIBRATING -> stepCalibration(result, quality)
            CaptureStage.RECORDING -> recordFrame(result, quality)
            else -> Unit
        }
    }

    /** 품질 통과 여부가 바뀔 때만 소리로 알린다. 매 프레임 울리면 소음이 된다. */
    private fun announceQuality(quality: CaptureQuality) {
        if (lastQualityPassed == quality.passed) return
        lastQualityPassed = quality.passed
        cues.play(
            if (quality.passed) CaptureCues.Cue.QUALITY_OK else CaptureCues.Cue.FAILED,
        )
    }

    private fun stepCalibration(result: PoseResult, quality: CaptureQuality) {
        // 품질이 깨지면 처음부터 다시 모은다. 조건이 어긋난 프레임이 섞인 기준선은
        // 이후 모든 특징을 조용히 틀리게 만든다.
        if (!quality.passed) {
            calibrationFrames.clear()
            calibrationStartMs = null
            _uiState.update { it.copy(calibrationRemainingMs = CALIBRATION_DURATION_MS) }
            return
        }

        val start = calibrationStartMs ?: result.timestampMs.also { calibrationStartMs = it }
        calibrationFrames += result.pose to result.aspectRatio
        val elapsed = result.timestampMs - start
        val remaining = (CALIBRATION_DURATION_MS - elapsed).coerceAtLeast(0)
        _uiState.update { it.copy(calibrationRemainingMs = remaining) }
        if (remaining > 0) return

        val calibration = StandingCalibration.from(calibrationFrames)
        if (calibration == null) {
            _uiState.update {
                it.copy(calibrationProblems = listOf(StandingCalibration.Problem.TOO_FEW_FRAMES))
            }
            cues.play(CaptureCues.Cue.FAILED)
            calibrationFrames.clear()
            calibrationStartMs = null
            return
        }

        val validation = calibration.validate()
        _uiState.update { it.copy(calibrationProblems = validation.problems) }
        if (!validation.passed) {
            cues.play(CaptureCues.Cue.FAILED)
            calibrationFrames.clear()
            calibrationStartMs = null
            return
        }
        beginRecording(calibration, result)
    }

    private fun recordFrame(result: PoseResult, quality: CaptureQuality) {
        val recorder = recorder ?: return

        // 품질이 깨지면 소리로 알린다. 측면 촬영에서는 화면을 볼 수 없으므로 이게
        // 유일한 통보 수단이다.
        if (lastQualityPassed != quality.passed) {
            lastQualityPassed = quality.passed
            if (!quality.passed) cues.play(CaptureCues.Cue.QUALITY_LOST)
        }

        // 필터 전·후 좌표를 모두 넘긴다. 지금은 PoseAnalyzer가 필터를 적용하지 않으므로
        // 두 값이 같다 — 필터를 넣게 되면 여기만 바꾸면 되고, 원본이 남아 있어 과거
        // 데이터를 다시 계산할 수 있다.
        val rep = recorder.accept(
            rawPose = result.pose,
            filteredPose = result.pose,
            timestampMs = result.timestampMs,
        )

        // 프레임은 매번 기록한다. 채널로 넘기므로 분석 스레드는 막히지 않는다.
        recorder.build().frames.lastOrNull()?.let(logSession::logFrame)

        if (rep != null) {
            _uiState.update { it.copy(reps = it.reps + rep) }
            logSession.logReps(_uiState.value.reps)
            if (rep.counted) cues.play(CaptureCues.Cue.REP_DONE)
        }
    }

    // ── 엔진 ────────────────────────────────────────────────

    private fun loadEngine() {
        viewModelScope.launch {
            _uiState.update { it.copy(engineReady = false, engineError = null) }
            val previous = engine
            engine = null
            val created = withContext(Dispatchers.IO) {
                previous?.close()
                runCatching {
                    PoseEngineHandle(
                        RtmPoseEngine.create(getApplication<Application>().assets),
                    )
                }
            }
            created
                .onSuccess { loaded ->
                    engine = loaded
                    _uiState.update {
                        it.copy(engineReady = true, modelName = loaded.modelName)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(engineError = e.message ?: e::class.simpleName ?: "알 수 없는 오류")
                    }
                }
        }
    }

    fun toggleCamera() {
        // 렌즈가 바뀌면 캘리브레이션 기준선이 무효다 — 좌우가 뒤집히고 화각도 다르다.
        _uiState.update {
            it.copy(
                lensFacing = if (it.isFrontCamera) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                },
                calibration = null,
                result = null,
            )
        }
    }

    private fun trackFps(timestampMs: Long): Double {
        val previous = lastFrameTimestampMs
        lastFrameTimestampMs = timestampMs
        if (previous == null) return _uiState.value.analysisFps
        val interval = (timestampMs - previous).toFloat()
        val median = fpsWindow.push(interval) ?: return _uiState.value.analysisFps
        smoothedFrameIntervalMs = if (smoothedFrameIntervalMs <= 0.0) {
            median.toDouble()
        } else {
            smoothedFrameIntervalMs * 0.9 + median * 0.1
        }
        return if (smoothedFrameIntervalMs > 0) 1000.0 / smoothedFrameIntervalMs else 0.0
    }

    override fun onCleared() {
        super.onCleared()
        logSession.finish()
        cues.release()
        engine?.close()
        engine = null
    }

    private companion object {
        /** 문서 §6 — 2~3초. 짧으면 평균 표본이 부족하고 길면 서 있기 힘들다. */
        const val CALIBRATION_DURATION_MS = 3_000L

        fun dayStamp(): String =
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

        fun isoNow(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    }
}
