package com.mist.formchecker.ui.screen.workout

import android.app.Application
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.Delegate
import com.mist.formchecker.poseengine.MoveNetPoseEngine
import com.mist.formchecker.poseengine.PoseEngine
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
data class WorkoutUiState(
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

    private var engine: PoseEngine? = null

    /**
     * 자세 판정기.
     *
     * 지금은 잠정 임계값(`SquatAnalyzerConfig` 기본값)으로 생성한다. 데이터·모델 담당이
     * AI Hub 실측 분포로 확정한 값을 전달하면 **여기서 설정만 갈아끼우면 되고** 판정
     * 로직은 건드리지 않는다.
     */
    private val formAnalyzer = SquatFormAnalyzer(SquatAnalyzerConfig())

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
    private fun loadEngine() {
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    MoveNetPoseEngine.create(getApplication<Application>().assets)
                }
            }
            created
                .onSuccess { loaded ->
                    engine = loaded
                    _uiState.update {
                        it.copy(
                            engineReady = true,
                            activeDelegate = loaded.activeDelegate,
                            modelLoadMillis = loaded.modelLoadTimeNanos / 1_000_000,
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

        _uiState.update { state ->
            state.copy(
                result = result,
                // 판정은 여기서 한다 — 사용자가 각도를 바꾸면 다음 프레임부터 바로
                // 반영되어야 하는데, 카메라에 붙은 분석기는 교체되지 않아 각도 변경을
                // 알 수 없다.
                form = formAnalyzer.analyze(
                    pose = result.pose,
                    frameAspectRatio = result.aspectRatio,
                    cameraAngle = state.cameraAngle,
                ),
                inferenceMillis = smoothedInferenceMillis,
            )
        }
    }

    private fun onFrameDropped() {
        _uiState.update { it.copy(droppedFrames = it.droppedFrames + 1) }
    }

    fun toggleCamera() {
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
        _uiState.update { if (it.cameraAngle == angle) it else it.copy(cameraAngle = angle, form = null) }
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
        engine = null
    }

    private companion object {
        /** 표시용 스무딩 계수. 값이 클수록 최근 프레임에 민감해진다. */
        const val EMA_ALPHA = 0.2
    }
}
