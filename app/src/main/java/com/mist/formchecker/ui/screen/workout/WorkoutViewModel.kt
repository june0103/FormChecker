package com.mist.formchecker.ui.screen.workout

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.poseengine.AngleEma
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.CountingThresholds
import com.mist.formchecker.poseengine.Delegate
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.FootSample
import com.mist.formchecker.poseengine.KeypointType
import com.mist.formchecker.poseengine.MedianWindow
import com.mist.formchecker.poseengine.MoveNetPoseEngine
import com.mist.formchecker.poseengine.Pose
import com.mist.formchecker.poseengine.PoseEngineHandle
import com.mist.formchecker.poseengine.RepEvent
import com.mist.formchecker.poseengine.RepFrame
import com.mist.formchecker.poseengine.RepState
import com.mist.formchecker.poseengine.RepStateMachine
import com.mist.formchecker.poseengine.RepSummary
import com.mist.formchecker.poseengine.RtmPoseEngine
import com.mist.formchecker.poseengine.SquatAnalyzerConfig
import com.mist.formchecker.poseengine.SquatForm
import com.mist.formchecker.poseengine.SquatFormAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 관절 각도 확인 단계의 화면 상태.
 *
 * rep 카운팅·세션 저장은 아직 없다. 지금은 "카메라가 켜지고 관절 각도가 실제로 계산되는가"만
 * 확인하는 것이 목표다.
 */
/**
 * 포즈 모델 선택.
 *
 * [RTMPOSE]가 기본이다. MoveNet 17개(COCO)에는 발 키포인트가 없어 발뒤꿈치 들림과
 * 무릎-발끝 위치 관계를 관측할 수 없는데, 둘 다 스쿼트 판정의 핵심 항목이기 때문이다.
 *
 * [MOVENET]을 남겨둔 이유는 두 가지다.
 * 1. **성능 비교 근거** — 설계문서 8장의 추론 지연 비교에 쓸 수 있다. GPU delegate 비교가
 *    불가능해진 상황(`GATHER_ND` 미지원)에서 모델 간 비교가 대안이 된다.
 * 2. **저사양 기기 폴백 여지** — RTMPose가 30fps 예산에 걸치므로, 느린 기기에서 대안이 필요할
 *    수 있다.
 */
enum class PoseModel(val label: String) {
    RTMPOSE("RTMPose 26"),
    MOVENET("MoveNet 17"),
}

data class WorkoutUiState(
    val poseModel: PoseModel = PoseModel.RTMPOSE,
    val modelName: String = "",
    val engineReady: Boolean = false,
    val engineError: String? = null,
    /** 실제로 사용 중인 가속기. GPU를 요청해도 실패하면 CPU로 폴백되므로 요청값과 다를 수 있다. */
    val activeDelegate: Delegate? = null,
    /** 모델 로딩에 걸린 시간(ms). 설계문서 8장 콜드스타트 측정의 일부. */
    val modelLoadMillis: Long = 0,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    /**
     * 사용자가 고른 촬영 각도. 어느 판정을 켤지가 여기서 갈린다.
     *
     * 설계문서 395행이 스쿼트를 측면으로 지정하고, 깊이가 rep 카운팅의 근거이므로
     * 측면이 기본값이다. 무릎 정렬을 보려면 정면으로 전환해야 한다.
     */
    val cameraAngle: CameraAngle = CameraAngle.SIDE,
    val result: PoseResult? = null,
    val form: SquatForm? = null,
    // ── rep 카운팅 ──────────────────────────────────────────
    /** 완주한 rep 수. 카메라·모델 전환으로 상태머신을 재설정해도 유지된다. */
    val repCount: Int = 0,
    /** 현재 상태머신 상태. 개발 중 동작 확인용으로 화면에 표시한다. */
    val repState: RepState = RepState.IDLE,
    /** 마지막으로 완주한 rep의 요약. */
    val lastRep: RepSummary? = null,
    /** 마지막 rep의 깊이 판정. 카운팅과 분리해 rep 종료 후 별도로 평가한 결과다. */
    val lastRepDepth: DepthLevel? = null,
    /** 마지막으로 중단된 사유. 왜 안 세어졌는지 사용자·개발자가 알 수 있게 한다. */
    val lastAbortReason: RepEvent.Aborted.Reason? = null,
    /** 실측 분석 프레임레이트. 스무딩 창 크기 산출과 성능 확인에 쓴다. */
    val analysisFps: Double = 0.0,
    /** 추론에 실패해 건너뛴 프레임 수. 설계문서 8장의 dropped_frame_ratio 재료. */
    val droppedFrames: Int = 0,
    /** 최근 추론 지연(ms). */
    val inferenceMillis: Double = 0.0,
) {
    val isFrontCamera: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT
}

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WorkoutUiState())
    val uiState: StateFlow<WorkoutUiState> = _uiState.asStateFlow()

    private var engine: PoseEngineHandle? = null

    /**
     * 자세 판정기.
     *
     * 지금은 잠정 임계값(`SquatAnalyzerConfig` 기본값)으로 생성한다. 데이터·모델 담당이
     * AI Hub 실측 분포로 확정한 값을 전달하면 **여기서 설정만 갈아끼우면 되고** 판정
     * 로직은 건드리지 않는다.
     */
    private val config = SquatAnalyzerConfig()
    private val formAnalyzer = SquatFormAnalyzer(config)

    // ── rep 카운팅 ──────────────────────────────────────────

    private val countingThresholds = CountingThresholds()

    /** 상태 전이 안정화용. 위상 지연이 있어 최저점 탐색에는 쓰지 않는다. */
    private val kneeEma = AngleEma(config.smoothing.emaTimeConstantMs)

    /**
     * 특징 집계·최저점 탐색용. 창 크기는 실측 fps에서 시간 폭을 역산해 정한다.
     *
     * 고정 프레임 수로 두면 분석 fps가 변할 때 실제 시간 폭이 달라진다. RTMPose 추론이
     * 26~36ms로 흔들려 fps가 일정하지 않다.
     */
    private var kneeMedian = MedianWindow(
        MedianWindow.sizeFor(config.smoothing.medianWindowMs, ASSUMED_FPS),
    )

    private val stateMachine = RepStateMachine(
        thresholds = countingThresholds,
        cameraAngle = { _uiState.value.cameraAngle },
    )

    /** 실측 프레임레이트. 프레임 간격의 이동평균에서 역산한다. */
    private var smoothedFrameIntervalMs = 0.0
    private var lastFrameTimestampMs: Long? = null

    /** 추론 지연 표시용 지수이동평균. 프레임마다 값이 튀면 읽을 수가 없다. */
    private var smoothedInferenceMillis = 0.0

    init {
        loadEngine()
    }

    /**
     * 모델을 백그라운드에서 로드한다.
     *
     * 메인스레드에서 로드하면 화면이 그대로 멈추고 콜드스타트가 길어진다. 설계문서 8장이
     * "모델 로딩을 메인스레드 블로킹 → 백그라운드 비동기로 바꾼 전/후 비교"를 측정 항목으로
     * 두고 있어, 비동기 로딩은 처음부터 지켜야 할 기준선이다.
     */
    private fun loadEngine(model: PoseModel = _uiState.value.poseModel) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(engineReady = false, engineError = null, result = null, form = null)
            }
            val previous = engine
            engine = null

            val created = withContext(Dispatchers.IO) {
                // 진행 중인 추론이 끝난 뒤 해제된다. 그냥 close()하면 분석 스레드가
                // 쓰고 있는 네이티브 핸들을 해제해 프로세스가 죽는다.
                previous?.close()
                runCatching {
                    val assets = getApplication<Application>().assets
                    val loaded = when (model) {
                        PoseModel.MOVENET -> MoveNetPoseEngine.create(assets)
                        PoseModel.RTMPOSE -> RtmPoseEngine.create(assets)
                    }
                    PoseEngineHandle(loaded)
                }
            }
            created
                .onSuccess { loaded ->
                    engine = loaded
                    smoothedInferenceMillis = 0.0
                    _uiState.update {
                        it.copy(
                            engineReady = true,
                            modelName = loaded.modelName,
                            activeDelegate = loaded.activeDelegate,
                            modelLoadMillis = loaded.modelLoadTimeNanos / 1_000_000,
                            droppedFrames = 0,
                            inferenceMillis = 0.0,
                        )
                    }
                }
                .onFailure { e ->
                    // 설계문서 6장 ModelLoadFailed. 화면을 죽이지 않고 사유를 노출한다.
                    _uiState.update {
                        it.copy(engineError = e.message ?: e::class.simpleName ?: "알 수 없는 오류")
                    }
                }
        }
    }

    /**
     * 포즈 모델을 전환한다.
     *
     * 엔진을 교체하면 분석기도 새로 만들어져야 하므로 `engineReady`를 내렸다가 올린다.
     * 화면 쪽에서 `engineReady` 변화를 보고 분석기를 다시 생성한다.
     */
    fun selectPoseModel(model: PoseModel) {
        if (_uiState.value.poseModel == model) return
        // 모델이 바뀌면 각도 시계열이 끊기고 키포인트 정확도 특성도 달라진다. rep 수는
        // 유지하되 진행 중인 rep은 버린다.
        resetRepTracking()
        _uiState.update { it.copy(poseModel = model, repState = RepState.IDLE) }
        loadEngine(model)
    }

    /** 엔진이 준비된 뒤에만 분석기를 만들 수 있다. */
    fun createAnalyzer(): PoseAnalyzer? {
        val ready = engine ?: return null
        return PoseAnalyzer(
            engine = ready,
            onResult = ::onResult,
            onFrameDropped = ::onFrameDropped,
        )
    }

    private fun onResult(result: PoseResult) {
        val millis = result.pose.inferenceTimeNanos / 1_000_000.0
        smoothedInferenceMillis =
            if (smoothedInferenceMillis == 0.0) millis
            else smoothedInferenceMillis * (1 - EMA_ALPHA) + millis * EMA_ALPHA

        val fps = trackFps(result.timestampMs)

        // 판정은 여기서 한다 — 사용자가 각도를 바꾸면 다음 프레임부터 바로 반영되어야
        // 하는데, 카메라에 붙은 분석기는 교체되지 않아 각도 변경을 알 수 없다.
        val form = formAnalyzer.analyze(
            pose = result.pose,
            frameAspectRatio = result.aspectRatio,
            cameraAngle = _uiState.value.cameraAngle,
        )

        val event = stateMachine.update(buildRepFrame(result, form))

        _uiState.update { state ->
            var next = state.copy(
                result = result,
                form = form,
                repState = stateMachine.state,
                analysisFps = fps,
                inferenceMillis = smoothedInferenceMillis,
            )
            next = when (event) {
                is RepEvent.Completed -> next.copy(
                    repCount = state.repCount + 1,
                    lastRep = event.summary,
                    // 깊이는 카운팅과 분리해 rep이 끝난 뒤 최저 각도로 별도 판정한다.
                    // 설계문서 3.2절의 모순(BOTTOM < 100도 이면서 깊이부족 >= 120도)을
                    // 피하려면 이 두 판단이 섞이지 않아야 한다.
                    lastRepDepth = event.summary.aggregate.minKneeAngle
                        ?.let(config.form::depthLevelOf),
                    lastAbortReason = null,
                )
                is RepEvent.Aborted -> next.copy(lastAbortReason = event.reason)
                is RepEvent.Started -> next.copy(lastAbortReason = null)
                null -> next
            }
            next
        }
    }

    /**
     * 무릎 각도를 스무딩해 상태머신 입력을 만든다.
     *
     * 두 종류의 각도를 함께 넘기는 이유: 상태 전이는 EMA(안정적, 지연 허용), 최저점
     * 탐색과 특징 집계는 median(지연 없음)을 쓴다. `rawKneeAngle`을 따로 넘기는 이유는
     * EMA가 저신뢰 프레임에서 직전 값을 유지하도록 설계돼 있어, 스무딩된 값만으로는
     * "실제로 측정된 프레임"을 구분할 수 없기 때문이다.
     */
    private fun buildRepFrame(result: PoseResult, form: SquatForm): RepFrame {
        val raw = form.kneeAngles.representative
        val pose = result.pose
        return RepFrame(
            timestampMs = result.timestampMs,
            rawKneeAngle = raw,
            smoothedKneeAngle = kneeEma.update(raw, result.timestampMs),
            medianKneeAngle = kneeMedian.push(raw),
            leftKneeAngle = form.kneeAngles.left,
            rightKneeAngle = form.kneeAngles.right,
            hipAngle = form.hipAngles.representative,
            torsoLeanDegrees = form.torsoLeanDegrees,
            kneeAsymmetryDegrees = form.kneeAngles.asymmetry,
            leftFoot = FootSample.from(pose, FootSample.Side.LEFT),
            rightFoot = FootSample.from(pose, FootSample.Side.RIGHT),
            minLegConfidence = minLegConfidence(pose),
        )
    }

    /** 판정에 쓰이는 하체 키포인트 중 최저 confidence. 낮으면 그 시점 값을 신뢰할 수 없다. */
    private fun minLegConfidence(pose: Pose): Float = LEG_KEYPOINTS.minOf { pose[it].confidence }

    /**
     * 실측 fps를 추적하고, 필요하면 median 창 크기를 다시 계산한다.
     *
     * 창을 교체하면 버퍼가 비므로 rep 진행 중에는 바꾸지 않는다 — 최저점 탐색 도중에
     * 창이 리셋되면 그 rep의 특징이 왜곡된다.
     */
    private fun trackFps(timestampMs: Long): Double {
        val previous = lastFrameTimestampMs
        lastFrameTimestampMs = timestampMs
        if (previous == null) return _uiState.value.analysisFps

        val interval = (timestampMs - previous).toDouble()
        if (interval <= 0.0) return _uiState.value.analysisFps
        smoothedFrameIntervalMs =
            if (smoothedFrameIntervalMs == 0.0) interval
            else smoothedFrameIntervalMs * (1 - EMA_ALPHA) + interval * EMA_ALPHA

        val fps = 1000.0 / smoothedFrameIntervalMs
        val desired = MedianWindow.sizeFor(config.smoothing.medianWindowMs, fps)
        val idle = stateMachine.state == RepState.IDLE || stateMachine.state == RepState.STANDING
        if (desired != kneeMedian.size && idle) {
            kneeMedian = MedianWindow(desired)
        }
        return fps
    }

    /** 카메라·모델 전환처럼 시계열이 끊기는 경우 스무딩과 상태머신을 초기화한다. */
    private fun resetRepTracking() {
        stateMachine.reset()
        kneeEma.reset()
        kneeMedian.reset()
        smoothedFrameIntervalMs = 0.0
        lastFrameTimestampMs = null
    }

    private fun onFrameDropped() {
        _uiState.update { it.copy(droppedFrames = it.droppedFrames + 1) }
    }

    fun toggleCamera() {
        // 렌즈가 바뀌면 각도 시계열이 끊긴다. 초기화하지 않으면 직전 자세에서 이어지는
        // 것으로 오인해 없는 rep이 만들어질 수 있다. rep 수는 유지한다.
        resetRepTracking()
        _uiState.update {
            it.copy(
                lensFacing = if (it.isFrontCamera) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                },
                // 렌즈가 바뀌면 직전 프레임의 스켈레톤이 잠깐 남아 어색하므로 지운다.
                result = null,
                form = null,
                repState = RepState.IDLE,
            )
        }
    }

    /**
     * 촬영 각도를 바꾼다.
     *
     * 판정 항목이 통째로 갈리므로 직전 판정 결과는 버린다 — 측면 판정 결과가 정면
     * 화면에 잠깐 남아 보이면 사용자가 잘못 읽는다.
     */
    fun selectCameraAngle(angle: CameraAngle) {
        if (_uiState.value.cameraAngle == angle) return
        resetRepTracking()
        _uiState.update {
            it.copy(cameraAngle = angle, form = null, repState = RepState.IDLE)
        }
    }

    /** rep 카운터를 0으로 되돌린다. 세션 개념이 생기면 이 자리를 세션 시작이 대체한다. */
    fun resetRepCount() {
        resetRepTracking()
        _uiState.update {
            it.copy(
                repCount = 0,
                repState = RepState.IDLE,
                lastRep = null,
                lastRepDepth = null,
                lastAbortReason = null,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
        engine = null
    }

    private companion object {
        /** 표시용 스무딩 계수. 값이 클수록 최근 프레임에 민감해진다. */
        const val EMA_ALPHA = 0.2

        /** fps를 아직 측정하지 못했을 때 쓸 초기값. 첫 프레임 이후 실측으로 대체된다. */
        const val ASSUMED_FPS = 30.0

        /** 판정에 쓰이는 하체 키포인트. confidence 최솟값 산출 대상. */
        val LEG_KEYPOINTS = listOf(
            KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
            KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
            KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE,
        )
    }
}
