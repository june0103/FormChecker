package com.mist.formchecker.ui.screen.workout

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import android.os.Build
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.audio.AudioCues
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.data.CompletedRepInput
import com.mist.formchecker.data.SessionMetricsInput
import com.mist.formchecker.data.WorkoutRepository
import com.mist.formchecker.poseengine.RepScorer
import com.mist.formchecker.poseengine.AngleEma
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.CaptureQuality
import com.mist.formchecker.poseengine.CountingThresholds
import com.mist.formchecker.poseengine.Delegate
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.FootSample
import com.mist.formchecker.poseengine.FormCheck
import com.mist.formchecker.poseengine.FeedbackHold
import com.mist.formchecker.poseengine.FormThresholds
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.HeldWarning
import com.mist.formchecker.poseengine.KeypointType
import com.mist.formchecker.poseengine.KneeAlignment
import com.mist.formchecker.poseengine.MedianWindow
import com.mist.formchecker.poseengine.MoveNetPoseEngine
import com.mist.formchecker.poseengine.Pose
import com.mist.formchecker.poseengine.PoseEngineHandle
import com.mist.formchecker.poseengine.RepEvent
import com.mist.formchecker.poseengine.RepWarnings
import com.mist.formchecker.poseengine.RepFrame
import com.mist.formchecker.poseengine.RepPhase
import com.mist.formchecker.poseengine.RepState
import com.mist.formchecker.poseengine.ReadyPosture
import com.mist.formchecker.poseengine.RepStateMachine
import com.mist.formchecker.poseengine.RepSummary
import com.mist.formchecker.poseengine.RtmPoseEngine
import com.mist.formchecker.poseengine.SquatAnalyzerConfig
import com.mist.formchecker.poseengine.SquatForm
import com.mist.formchecker.poseengine.CaptureView
import com.mist.formchecker.poseengine.DepthBasis
import com.mist.formchecker.poseengine.FrameFeatures
import com.mist.formchecker.poseengine.Side
import com.mist.formchecker.poseengine.SquatFormAnalyzer
import com.mist.formchecker.poseengine.StandingCalibration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

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
    /**
     * 화면에 보여줄 자세 피드백. **[form]의 경고를 그대로 쓰지 않는다.**
     *
     * 프레임 단위 경고는 그 자세를 유지하는 동안만 참이라, 최저점에서 난 경고가 다 올라온
     * 뒤에는 이미 사라져 있다(실측 상승 구간 p95 1,171ms). [FeedbackHold]가 읽을 수 있는
     * 시간만큼 붙잡아 두고, 한 항목에 하나만 남긴다.
     */
    val heldWarnings: List<HeldWarning> = emptyList(),
    // ── rep 카운팅 ──────────────────────────────────────────
    /** 완주한 rep 수. 카메라·모델 전환으로 상태머신을 재설정해도 유지된다. */
    val repCount: Int = 0,
    /** 현재 상태머신 상태. 개발 중 동작 확인용으로 화면에 표시한다. */
    val repState: RepState = RepState.IDLE,
    /** 마지막으로 완주한 rep의 요약. */
    val lastRep: RepSummary? = null,
    /** 마지막 rep의 깊이 판정. 카운팅과 분리해 rep 종료 후 별도로 평가한 결과다. */
    val lastRepDepth: DepthLevel? = null,
    /**
     * [lastRepDepth]를 무엇으로 판정했는지.
     *
     * 두 기준은 환산되지 않으므로 어느 쪽을 썼는지 남겨야 한다. 기준선이 생기거나 사라질 때
     * 판정이 조용히 바뀌면 리포트에서 원인을 찾을 수 없다.
     */
    val lastRepDepthBasis: DepthBasis? = null,
    /** 마지막으로 중단된 사유. 왜 안 세어졌는지 사용자·개발자가 알 수 있게 한다. */
    val lastAbortReason: RepEvent.Aborted.Reason? = null,
    /** 실측 분석 프레임레이트. 스무딩 창 크기 산출과 성능 확인에 쓴다. */
    val analysisFps: Double = 0.0,
    /** 추론에 실패해 건너뛴 프레임 수. 설계문서 8장의 dropped_frame_ratio 재료. */
    val droppedFrames: Int = 0,
    /** 최근 추론 지연(ms). */
    val inferenceMillis: Double = 0.0,
    // ── 기준 자세 ───────────────────────────────────────────
    val calibrationStage: CalibrationStage = CalibrationStage.NONE,
    /**
     * 선 자세 기준선. null이면 아직 재지 않았다.
     *
     * 정규화된 특징(깊이 비율, 상체 숙임, 뒤꿈치 들림)은 전부 이 값을 분모로 쓴다.
     * 없으면 그 특징을 계산할 수 없다 — 각도만으로는 대체되지 않는다.
     */
    val calibration: StandingCalibration? = null,
    /** 남은 유지 시간(ms). 화면에 카운트다운으로 보여준다. */
    val calibrationRemainingMs: Long = 0,

    /**
     * 측정 시작까지 줄 준비 시간(초). **설정에서 고른다.**
     *
     * 예전에는 이 화면에 선택 줄이 있었다. 삼각대 위치나 방 크기에 따라 크게 달라진다는
     * 이유였는데, **한 사람에게는 거의 안 바뀌는 값**이라 매 세션 화면을 차지할 이유가
     * 없었다. 설정으로 옮기고 값을 기억한다.
     *
     * 화면은 이 값을 버튼 문구에만 쓴다("5초 후 시작") — 여기서 바꿀 수는 없다.
     */
    val calibrationPrepSeconds: Int = CalibrationPrep.DEFAULT_SECONDS,

    /** 준비 시간 카운트다운 잔여(ms). [CalibrationStage.PREPARING]에서만 의미가 있다. */
    val calibrationPrepRemainingMs: Long = 0,

    /**
     * 지금 프레임에서 기준 자세에 필요한 관절이 전부 보이나.
     *
     * 준비 시간 동안 이걸 보여주면 사용자가 **카운트다운이 끝나기 전에** 자기 위치를 고칠
     * 수 있다. 없으면 3초를 다 기다린 뒤에야 실패를 알게 된다.
     */
    val calibrationSubjectVisible: Boolean = false,
    /**
     * 준비 자세 점검. 기준 자세를 재는 동안 **실시간으로** 갱신되고, 측정이 끝나면
     * 창 전체로 다시 계산한 값이 들어온다.
     *
     * 실시간으로 보여주는 이유는 [calibrationSubjectVisible]과 같다 — 측정이 끝난 뒤에
     * "발이 좁았어요"라고 말하면 사용자는 이미 스쿼트를 시작한 뒤다.
     */
    val readyPosture: ReadyPosture = ReadyPosture.EMPTY,
    /**
     * 미디어 볼륨이 0인가.
     *
     * ## 왜 상태로 두는가
     * 소리가 이 화면의 **주 피드백 채널**이다 — 폰을 세워두고 떨어져 서면 횟수 말고는
     * 글씨가 읽히지 않는다. 그런데 톤은 `STREAM_MUSIC`으로 나가므로 미디어 볼륨이 0이면
     * 아무 소리도 안 난다.
     *
     * 그 상태를 알리지 않으면 사용자는 **기능이 고장난 것으로 읽는다.** 실제로 개발 기기의
     * 미디어 볼륨이 0이어서 톤을 넣고도 들리지 않는 상황을 겪었다.
     *
     * 볼륨을 앱이 올리지는 않는다 — 사용자 기기 설정이고, 조용히 소리를 키우는 앱은
     * 더 나쁘다.
     */
    val soundMuted: Boolean = false,
    val calibrationProblems: List<StandingCalibration.Problem> = emptyList(),
    /**
     * 측면에서 카메라에 가까운 쪽. 캘리브레이션에서 정하고 그 뒤 바꾸지 않는다.
     *
     * 프레임마다 신뢰도 높은 다리로 갈아타면 관절이 전환되며 각도가 튄다(문서 §4.3).
     */
    val activeSide: Side? = null,
    /**
     * 기준선으로 계산한 현재 프레임 특징. 기준선이 없으면 null.
     *
     * 지금은 화면에 값을 띄워 **기준선이 실제로 맞는지 눈으로 확인하는** 용도다. 판정을
     * 이 값으로 옮기는 것은 다음 단계다 — 옮기기 전에 기기에서 값이 말이 되는지 봐야 한다.
     */
    val features: FrameFeatures? = null,
    /**
     * 지금 판정에 쓰는 무릎–발끝 허용폭. **개발 화면에서만 바꿀 수 있다.**
     *
     * 화면에 항상 띄우는 이유: 한 세션 안에서 값을 바꾸면 이미 세어진 rep은 옛 값으로
     * 판정된 것인데 `rep_records`에는 그 사실이 남지 않는다. 최소한 지금 무엇으로 재고
     * 있는지는 보여야 한다([WorkoutViewModel.selectKneeToeTolerance]).
     */
    val kneeToeTolerance: Float = KneeTolerance.DEFAULT,
) {
    val isFrontCamera: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /**
     * 지금 횟수를 세고 있나. 기준 자세를 다 잰 뒤에만 센다.
     *
     * ## 왜 막는가
     * 카메라 앞으로 걸어가고 자리를 잡는 동안 무릎 각도가 오르내려 **하지 않은 rep이
     * 세어진다.** 유령 rep은 그 세션 기록을 그대로 오염시키고, 나중에 어느 rep이 진짜였는지
     * 알 방법이 없다.
     *
     * ## 왜 이걸 화면에 노출하는가
     * 막는 것만으로는 더 나쁘다 — 스쿼트를 하는데 숫자가 0에 머물면 사용자는 앱이 고장난
     * 줄로 읽는다. 두 운동 화면이 이 값을 보고 "왜 안 세는지"를 말한다.
     */
    val countingEnabled: Boolean get() = calibrationStage == CalibrationStage.READY

    /**
     * 정규화된 자세 특징을 쓸 수 있는가.
     *
     * 카운팅과는 무관하다 — 무릎 각도만 쓰는 rep 카운팅은 기준선 없이도 동작한다
     * (문서 §2.3의 카운팅·자세 평가 분리와 같은 이유).
     */
    val formNormalized: Boolean get() = calibration != null

    /** 측면인데 활성측을 못 정했으면 측면 특징을 계산할 수 없다. */
    val captureView: CaptureView?
        get() = when {
            cameraAngle == CameraAngle.FRONT -> CaptureView.FRONT
            activeSide == Side.LEFT -> CaptureView.SIDE_LEFT
            activeSide == Side.RIGHT -> CaptureView.SIDE_RIGHT
            else -> null
        }
}

/**
 * 기준 자세 측정 단계.
 *
 * ## 카운팅이 [READY]에서만 돌아간다
 * 이전에는 기준선이 **선택**이었다 — "rep 카운팅은 무릎 각도만 쓰므로 기준선 없이도
 * 동작하고, 막으면 얻는 것 없이 운동 흐름만 끊긴다"는 판단이었다. 실기에서 뒤집혔다:
 * 사용자가 카메라 앞으로 걸어가고 자리를 잡는 동안 무릎 각도가 오르내려 **하지 않은 rep이
 * 세어졌다.** 유령 rep은 세션 기록을 그대로 오염시키고 나중에 걸러낼 방법이 없다.
 *
 * 문서 §2.3의 "카운팅과 자세 평가 분리"는 유효하다 — 분리한 것은 **판정 기준**이고, 여기서
 * 묶은 것은 **언제부터 시작하는가**다. 기준선 없이 카운팅이 기술적으로 가능한 것과, 그때
 * 세는 것이 옳은 것은 다른 문제였다.
 *
 * 막는 것만으로는 더 나쁘다. 왜 안 세는지 반드시 화면에 알린다
 * ([WorkoutUiState.countingEnabled]).
 */
/**
 * 기준 자세 측정 전에 줄 준비 시간.
 *
 * `WorkoutViewModel`의 companion이 아니라 밖에 두는 이유: `WorkoutUiState`의 기본값이
 * 이 값을 참조하는데, 그 데이터 클래스는 ViewModel보다 먼저 선언된다.
 */
/**
 * 무릎–발끝 허용폭 실험값.
 *
 * ## 왜 고를 수 있게 두었나
 * 실측 12세션 240 rep을 프로덕션 공식으로 다시 계산하니, **정상 의도 세션에서도** 현재
 * 값(0.10)이 rep의 62%에 경고를 냈다. 임계값별 경고율은 계산으로 나온다:
 *
 * | 허용폭 | 사람내 MAD 배수 | rep 경고율(정상 의도 240 rep) |
 * |---|---|---|
 * | 0.10 (현재) | 3× | 62% |
 * | 0.125 | 3.8× | 50% |
 * | 0.15 | 4.5× | 45% |
 * | 0.20 | 6× | 34% |
 * | 0.25 | 7.5× | 18% |
 *
 * **하지만 이 표가 답을 주지 않는다.** 오류 의도 세션이 하나도 없어서 어느 값에서도
 * "오류를 잡는가"가 미검증이고, 넓힐수록 그 위험이 커진다. 그래서 계산이 아니라 **기기에서
 * 서서 확인해야** 하는 값이고, 이 목록이 그 자리다.
 *
 * ## 왜 0.30을 넣지 않았나
 * 실측 **선 자세** 편차 중앙값이 +0.309다. 허용폭이 그만큼 커지면 가만히 서 있는 자세의
 * 어긋남을 정상으로 인정하는 셈이라, 깊이 게이트가 존재하는 이유 자체가 무너진다.
 * 고를 수 있게 두면 누군가 "0.30에서는 경고가 안 뜨네"로 끝낼 것이다.
 *
 * `WorkoutViewModel`의 companion이 아니라 밖에 두는 이유는 [CalibrationPrep]과 같다 —
 * `WorkoutUiState`의 기본값이 이 값을 참조하고, 그 데이터 클래스가 ViewModel보다 먼저
 * 선언된다.
 */
object KneeTolerance {
    /** `FormThresholds.kneeToeToleranceRatio`의 실제 기본값과 같아야 한다. */
    val DEFAULT: Float = FormThresholds().kneeToeToleranceRatio

    /**
     * 설정의 "기준 완화"가 켜졌을 때 쓰는 값.
     *
     * 근거는 `FormThresholds.RELAXED_KNEE_TOE_TOLERANCE`에 있다 — 여기서 다시 적지 않는다.
     * 값이 두 곳에 적히면 한쪽만 바뀐다.
     */
    val RELAXED: Float = FormThresholds.RELAXED_KNEE_TOE_TOLERANCE

    /** 고를 수 있는 값. 첫 항목이 현재 확정값이다. */
    val OPTIONS: List<Float> = listOf(DEFAULT, 0.125f, RELAXED, 0.20f, 0.25f)

    /** 설정 토글이 정하는 값. 실서비스에서 쓰이는 두 값은 이것뿐이다. */
    fun forSetting(relaxed: Boolean): Float = if (relaxed) RELAXED else DEFAULT

    /**
     * 화면 표시용. 임계값 숫자를 쓰는 것은 개발 화면이라서 허용된다(설계문서 5.2절).
     *
     * 소수점 세 자리로 **폭을 맞춘다.** 뒤 0을 떼면 `0.1 / 0.125 / 0.15 / 0.2 / 0.25`가 되어
     * 자릿수가 들쭉날쭉해지고, 나란히 놓인 버튼에서 값의 크기 순서가 눈에 안 들어온다.
     */
    fun label(value: Float): String = "%.3f".format(value)
}

object CalibrationPrep {
    /** 기본값(초). 팔을 뻗어 버튼을 누르고 물러서는 거리를 기준으로 한 잠정값이다. */
    const val DEFAULT_SECONDS = 5

    /**
     * 고를 수 있는 값(초).
     *
     * 0은 "카메라가 이미 나를 보고 있다"(삼각대를 앞에 두고 화면에 손이 닿는 경우)를 위한
     * 값이다. 없애면 그런 배치에서도 매번 기다려야 한다.
     */
    val OPTIONS = listOf(0, 3, 5, 10)

    /** 카운트다운 갱신 주기(ms). 초 단위 표시라 이보다 잘게 볼 이유가 없다. */
    const val TICK_MS = 100L
}

enum class CalibrationStage {
    /** 아직 재지 않음. 카운팅은 정상 동작한다. */
    NONE,

    /**
     * 카메라 앞으로 이동할 시간을 주는 중. **아직 재지 않는다.**
     *
     * ## 왜 필요한가
     * 버튼은 손이 닿는 곳에서 누르고, 측정은 전신이 보이는 자리에서 해야 한다. 그 사이를
     * 걸어가는 동안 [COLLECTING]이 돌면 두 가지가 일어난다 — 관절이 안 잡히는 동안은
     * 타이머가 계속 리셋되고, **몸이 잡힌 직후 아직 자세를 잡는 중에 3초 창이 시작돼**
     * 흔들림으로 `UNSTABLE` 실패가 난다. 실기에서 이것 때문에 반복 실패했다.
     */
    PREPARING,

    /** 선 자세를 모으는 중. */
    COLLECTING,

    /** 기준선 확보. */
    READY,
    ;

    /** 지금 기준 자세를 재는 중인가. 준비 자세 안내를 켤 구간이다. */
    val isMeasuring: Boolean get() = this == PREPARING || this == COLLECTING
}

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    application: Application,
    private val repository: WorkoutRepository,
    private val settings: AppSettings,
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
    private var config = SquatAnalyzerConfig()
    private var formAnalyzer = SquatFormAnalyzer(config)

    /**
     * 무릎–발끝 허용폭을 바꿔 끼운다. **개발 화면 전용 실험 노브다.**
     *
     * ## 왜 필요한가
     * 실측 12세션 240 rep을 다시 계산해 보니 정상 의도 세션에서도 현재 값(0.10)이 rep의
     * **62%**에 경고를 냈다. 임계값을 옮기면 얼마나 달라지는지는 계산으로 나왔지만
     * (0.125→50% · 0.15→45% · 0.20→34% · 0.25→18%), **그 숫자가 실제로 서서 스쿼트할 때의
     * 체감과 맞는지는 계산으로 답할 수 없다.** 같은 사람이 같은 자리에서 값만 바꿔가며
     * 확인하는 자리가 이것이다.
     *
     * ## 저장하지 않는다
     * ViewModel이 사라지면 기본값으로 돌아간다. 설정에 넣지 않은 이유는 [AppSettings]에
     * 넣는 값은 사용자가 고르는 값이고, 이건 **측정용 노브**라서다 — 판정 기준이 사용자마다
     * 다르면 쌓인 `rep_records`를 나중에 해석할 수 없다.
     *
     * ## ⚠ 한 세션 안에서 바꾸면 기록이 섞인다
     * 이미 세어진 rep은 옛 값으로 판정된 것이고, `rep_records`에는 어느 값이었는지 남지
     * 않는다. 깨끗한 비교가 필요하면 **값마다 세션을 새로 시작할 것.** 그래서 이 함수는
     * 카운팅을 초기화하지 않는다 — 초기화하면 값을 바꿀 때마다 기준 자세를 다시 재야 해서
     * 실험 자체가 어려워진다. 대신 화면에 현재 값을 항상 띄운다.
     */
    fun selectKneeToeTolerance(value: Float) {
        if (value !in KneeTolerance.OPTIONS) return
        applyKneeToeTolerance(value)
    }

    /**
     * 허용폭을 실제로 갈아끼운다. 설정 토글([AppSettings.relaxedForm])과 개발 화면 버튼이
     * 공유하는 한 경로다 — 둘이 각자 갈아끼우면 한쪽이 화면 상태만 바꾸고 판정은 그대로인
     * 상태가 생긴다.
     */
    /**
     * "기준 완화" 설정을 판정에 반영한다. **두 항목을 함께 바꾼다.**
     *
     * - 무릎–발끝 허용폭 `0.10 ↔ 0.15`
     * - 깊이의 얕은 쪽 경계 `0.03 ↔ 0.10` (정강이 경로는 그 환산값)
     *
     * **깊이는 얕은 쪽만 넓힌다.** `deepDepthRatio`는 그대로다 — 더 깊게 앉는 것은 문제가
     * 아니므로 완화할 이유가 없고, 함께 밀면 완화를 켰을 때 "깊음"이 "적당"으로 내려앉는다.
     *
     * 개발 화면의 허용폭 버튼([selectKneeToeTolerance])과 충돌하지 않는다 — 이 flow는 설정이
     * 실제로 바뀔 때만 흘러오므로, 버튼으로 골라 둔 값이 되돌아가지 않는다.
     */
    /**
     * 설정 `기준 완화`가 켜져 있나. **rep마다 저장한다** — 나중에 임계값을 재분석할 때
     * 어느 기준으로 나온 판정인지 알아야 한다([CompletedRepInput]).
     *
     * 개발 화면의 허용폭 노브는 이 값을 바꾸지 않는다. 그쪽은 설정이 아니라 실험이고,
     * 실제로 쓰인 값은 `kneeTolerance`로 따로 남는다.
     */
    private var relaxedForm = false

    private fun applyRelaxedForm(relaxed: Boolean) {
        relaxedForm = relaxed
        val form = config.form
        val next = form.copy(
            kneeToeToleranceRatio = KneeTolerance.forSetting(relaxed),
            shallowToleranceRatio = if (relaxed) {
                FormThresholds.RELAXED_SHALLOW_TOLERANCE
            } else {
                form.parallelToleranceRatio
            },
            shallowShinTolerance = if (relaxed) {
                FormThresholds.RELAXED_SHALLOW_SHIN_TOLERANCE
            } else {
                form.parallelShinTolerance
            },
        )
        if (next == form) return
        config = config.copy(form = next)
        formAnalyzer = SquatFormAnalyzer(config)
        feedbackHold.reset()
        _uiState.update {
            it.copy(kneeToeTolerance = next.kneeToeToleranceRatio, heldWarnings = emptyList())
        }
    }

    private fun applyKneeToeTolerance(value: Float) {
        if (value == _uiState.value.kneeToeTolerance) return
        config = config.copy(form = config.form.copy(kneeToeToleranceRatio = value))
        formAnalyzer = SquatFormAnalyzer(config)
        // 붙잡아 둔 경고는 옛 기준으로 낸 것이다. 남겨두면 새 기준에서는 안 뜰 경고가
        // 화면에 2초 더 머문다.
        feedbackHold.reset()
        _uiState.update { it.copy(kneeToeTolerance = value, heldWarnings = emptyList()) }
    }

    // ── rep 카운팅 ──────────────────────────────────────────

    private val countingThresholds = CountingThresholds()

    /**
     * 자세 피드백을 화면에 붙잡아 두는 장치.
     *
     * 카운팅 스무딩과 나란히 둔다 — 둘 다 "프레임마다 튀는 것을 사람이 볼 수 있게 만드는"
     * 일이고, 판정 자체는 건드리지 않는다.
     */
    private val feedbackHold = FeedbackHold()

    /**
     * 소리 신호.
     *
     * ## 왜 필요한가
     * 이 화면은 폰을 세워두고 몇 걸음 떨어져 쓴다. 그 거리에서 **횟수 말고는 글씨가 읽히지
     * 않는다**(`기술선택_기록.md` 57번). 세어졌는지, 자세에 문제가 있었는지를 소리로 알 수
     * 있어야 한다 — 수집 화면이 같은 이유로 이미 쓰고 있다.
     *
     * 아무 스레드에서 불러도 된다([AudioCues] 참고).
     */
    private val cues = AudioCues()

    /**
     * 사용자가 촬영 방향을 직접 골랐나. **개발 화면에서만 참이 된다.**
     *
     * 사용자용 화면은 방향 선택 버튼을 없앴고 판별은 자동이다. 그런데 개발 화면에는 각도
     * 전환이 남아 있고, 그 화면의 목적은 **특정 각도의 판정을 확인하는 것**이다. 자동
     * 판별이 다음 프레임에 그 선택을 덮어쓰면 수동 전환이 아무 일도 하지 않는 버튼이 된다.
     *
     * 한 번 고르면 그 화면을 떠날 때까지 유지한다 — 기준 자세를 다시 재도 풀지 않는다.
     * 풀면 재측정 직후 자동 판별이 다시 덮어쓴다.
     */
    private var angleLockedByUser = false

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

    /**
     * 현재 rep에서 관측한 가장 깊은 지점의 깊이 비율.
     *
     * rep 요약의 `minKneeAngle`로는 비율 기준 판정을 할 수 없어 따로 모은다. 최댓값인
     * 이유는 힙이 내려갈수록 값이 커지기 때문이다(화면 y가 아래로 증가).
     */
    private var repMaxDepthRatio: Float? = null

    /**
     * 같은 것을 정강이 길이로 정규화한 값. 기준선이 없을 때 쓴다.
     *
     * 프레임 판정([SquatFormAnalyzer])과 같은 순서로 대체해야 한다 — 화면에는 프레임
     * 판정이, rep 요약에는 이쪽이 뜨는데 둘이 다른 기준을 쓰면 같은 rep을 다르게 읽는다.
     */
    private var repMaxShinDepth: Float? = null

    // ── 세션 기록 ───────────────────────────────────────────

    /** 이 화면에 들어온 시각. 세션의 `started_at`이다. */
    private val sessionStartedAtMs = System.currentTimeMillis()

    /**
     * 완료한 rep을 쌓아 둔다. 종료 시 한 번에 저장한다.
     *
     * rep마다 즉시 쓰지 않는 이유: 운동 중에는 프레임 처리가 가장 바쁘고, rep 완료는 그
     * 안에서 일어난다. 디스크 쓰기를 그 경로에 넣으면 추론 지연에 섞여 들어간다.
     * 세션 하나는 수십 rep이라 메모리에 두기에 충분히 작다.
     */
    private val completedReps = mutableListOf<CompletedRepInput>()

    /**
     * 현재 rep 구간에서 한 번이라도 발생한 경고.
     *
     * **최저점 한 프레임만 보면 안 된다** — 상체 숙임은 실측에서 하강 최대(41.1°)가 최저점
     * 최대(41.0°)보다 높았다. 구간 전체를 봐야 하강 중에만 나타난 경고를 놓치지 않는다.
     */
    private val repWarnings = mutableSetOf<FormWarning>()

    /**
     * 이 rep에서 게이트를 통과한 무릎 돌출값들. **rep이 끝날 때 중앙값으로 판정한다.**
     *
     * 프레임 단위로 판정하지 않는 이유는 `FormThresholds.kneeOverToeLimit` 주석에 있다 —
     * 허용폭이 0이라 프레임 하나라도 넘으면 정상 rep의 46%가 걸린다.
     */
    private val repKneeOverToe = mutableListOf<Float>()

    /**
     * 이 rep에서 무릎 편차가 가장 컸던 쪽과 그 크기.
     *
     * 프레임마다 좌우 중 나쁜 쪽이 바뀔 수 있으므로 **구간 전체에서 가장 큰 편차**가 나온
     * 쪽을 쓴다. 마지막 프레임의 쪽을 쓰면 상승 중 흔들림이 결론을 바꾼다.
     */
    private var repWorstKnee: Pair<Side, Float>? = null

    /**
     * 이 rep에서 경고 종류별로 문제가 났던 구간.
     *
     * ## 왜 구간을 남기는가
     * "무릎이 모여요"와 "무릎이 벌어져요"가 한 rep에 함께 날 수 있다. **언제인지를 말하지
     * 않으면 사용자는 둘 중 무엇을 언제 고쳐야 하는지 알 수 없다** — 내려갈 때 벌어지는
     * 것과 올라올 때 모이는 것은 고치는 방법이 다르다.
     *
     * 프레임 수가 아니라 **구간 집합**을 담는다. 구간마다 길이가 달라 프레임 수로 세면
     * 긴 구간이 항상 이긴다.
     */
    private val repWarningPhases = mutableMapOf<FormWarning, MutableSet<RepPhase>>()

    /** 준비 시간 카운트다운. 취소·재시작·화면 이탈 시 반드시 끊어야 한다. */
    private var prepJob: Job? = null

    /** 추론 지연 표본. p95를 내려면 EMA가 아니라 원표본이 필요하다. */
    private val inferenceSamples = mutableListOf<Float>()

    /** 분석에 성공한 프레임 수. `dropped_frame_ratio`의 분모다. */
    private var analyzedFrameCount = 0

    // ── 기준 자세 ───────────────────────────────────────────
    /** 캘리브레이션 창에 모인 프레임. (pose, aspectRatio). */
    private val calibrationFrames = mutableListOf<Pair<Pose, Float>>()
    private var calibrationStartMs: Long? = null

    /** 마지막으로 울린 카운트다운 초. 같은 초에 두 번 울리지 않게 한다. */
    private var lastCountdownSecond = Int.MAX_VALUE

    /**
     * 준비 자세 점검용 최근 프레임. **캘리브레이션 창과 별개로 돈다.**
     *
     * 두 가지 이유로 따로 둔다.
     * 1. 준비 시간([CalibrationStage.PREPARING])에는 `calibrationFrames`를 채우지 않는데,
     *    사용자가 자세를 고칠 수 있어야 하는 구간이 바로 거기다.
     * 2. 캘리브레이션 창은 필수 관절이 하나만 빠져도 **처음부터 다시 모은다.** 그 규칙을
     *    안내에 그대로 쓰면 몸이 잠깐 가려질 때마다 안내가 사라진다.
     *
     * 크기는 [READY_WINDOW_FRAMES]. 중앙값을 낼 만큼 크고, 사용자가 발을 옮기면 1초 안에
     * 반영될 만큼 작아야 한다.
     */
    private val readyFrames = ArrayDeque<Pair<Pose, Float>>()

    init {
        // 화면에 들어온 시점에 한 번. 걸어가기 전에 알아야 볼륨을 올릴 수 있다.
        refreshSoundMuted()
        loadEngine()

        // 설정이 바뀌면 이 화면을 다시 들어가지 않고 반영된다. 준비 시간은 측정을
        // 시작하기 전에만 쓰이므로, 진행 중인 카운트다운이 흔들릴 걱정은 없다 —
        // `startCalibration`이 시작 시점의 값을 읽어 그 창 내내 쓴다.
        viewModelScope.launch {
            settings.calibrationPrepSeconds.collect { seconds ->
                val value = seconds ?: CalibrationPrep.DEFAULT_SECONDS
                if (value != _uiState.value.calibrationPrepSeconds) {
                    _uiState.update { it.copy(calibrationPrepSeconds = value) }
                }
            }
        }

        // 처음 들어온 사람에게 동작 예시를 한 번 띄운다. 한 번만 읽는다 —
        // collect로 붙들면 "봤다"고 저장하는 순간 다시 흘러와 시트가 닫히자마자 열린다.
        viewModelScope.launch {
            if (!settings.squatExampleSeen.first()) {
                _showFormExample.value = true
            }
        }

        // 기준 완화 토글. 설정을 바꾸면 이 화면을 다시 들어가지 않고 반영된다.
        //
        // **개발 화면의 허용폭 버튼과 충돌하지 않는다** — 이 flow는 설정이 실제로 바뀔 때만
        // 다시 흘러오므로(DataStore가 값 변경 시 emit), 버튼으로 다른 값을 골라 둔 상태가
        // 설정 emit 때문에 되돌아가지 않는다. 설정을 바꾸면 그때는 설정이 이긴다.
        viewModelScope.launch {
            settings.relaxedForm.collect(::applyRelaxedForm)
        }
    }

    /**
     * 동작 예시를 **자동으로** 띄워야 하나. 처음 들어왔을 때만 true다.
     *
     * 사용자 화면만 본다 — 개발 화면은 판정 수치를 보는 자리라 안내 시트가 방해다.
     */
    private val _showFormExample = MutableStateFlow(false)
    val showFormExample: StateFlow<Boolean> = _showFormExample.asStateFlow()

    /** 예시를 닫았다. 다음부터는 버튼으로만 연다. */
    fun markFormExampleSeen() {
        _showFormExample.value = false
        viewModelScope.launch { settings.markSquatExampleSeen() }
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

        // 특징을 먼저 계산한다 — 판정이 이 값을 쓴다. 기준선이 없으면 null이고, 그때는
        // 판정이 각도 기준으로 대체된다.
        val features = featuresOf(result)

        // 판정은 여기서 한다 — 사용자가 각도를 바꾸면 다음 프레임부터 바로 반영되어야
        // 하는데, 카메라에 붙은 분석기는 교체되지 않아 각도 변경을 알 수 없다.
        val form = formAnalyzer.analyze(
            pose = result.pose,
            frameAspectRatio = result.aspectRatio,
            cameraAngle = _uiState.value.cameraAngle,
            features = features,
        )

        // 스무딩은 항상 먹인다 — 카운팅이 켜지는 순간 EMA·중앙값 창이 차 있어야 첫 rep이
        // 지연 없이 잡힌다. 상태머신에 넣을지만 게이트한다.
        val repFrame = buildRepFrame(result, form)

        // **기준 자세를 다 잰 뒤에만 센다.**
        //
        // 이전에는 카운팅이 캘리브레이션과 독립이었다 — "기준선 없이도 무릎 각도만으로
        // 셀 수 있고, 막으면 얻는 것 없이 운동만 끊긴다"는 판단이었다. 실기에서 그게
        // 뒤집혔다: 사용자가 카메라 앞으로 걸어가고 자리를 잡는 동안 무릎 각도가 오르내려
        // **하지 않은 rep이 세어진다.** 유령 rep은 세션 기록을 그대로 오염시킨다.
        //
        // 대신 왜 안 세어지는지 반드시 화면에 알린다([WorkoutUiState.countingEnabled]) —
        // 아무 말 없이 0에 머무는 것이 유령 rep보다 나쁘다.
        val event = if (_uiState.value.countingEnabled) {
            stateMachine.update(repFrame)
        } else {
            null
        }

        // rep이 시작될 때 비우고 그 뒤로 최댓값을 쌓는다. 시작 프레임부터 모아야 하므로
        // 이벤트를 받은 직후에 처리한다.
        analyzedFrameCount++
        inferenceSamples += millis.toFloat()

        if (event is RepEvent.Started) {
            repMaxDepthRatio = null
            repMaxShinDepth = null
            repWarnings.clear()
            repKneeOverToe.clear()
            repWorstKnee = null
            repWarningPhases.clear()
        }
        // rep 구간 안인가. 저장과 표시가 **같은 게이트**를 쓴다.
        val inRep = stateMachine.state.isInRep

        // ## 화면에도 rep 구간 게이트를 먹인다
        //
        // 저장 경로에는 처음부터 이 게이트가 있었는데(아래 `if (inRep)`) 화면에는 없었다.
        // 그래서 **가만히 서 있어도 "양발로 고르게 일어서세요"가 떴다** — 서 있는 사람에게
        // 일어서라고 말하는 문구다(사용자 신고, 2026-08-24).
        //
        // 근거는 임계값의 출처다. `hipShiftLimit`(0.10)은 **정상 의도 9명 794프레임의
        // 최저점 분포**에서 뽑았고, 무릎 쪽도 최저점 253 rep에서 뽑았다. 선 자세 프레임에
        // 그 값을 대는 것은 **도출 범위 밖에서 판정하는 것**이다 — 무릎이 깊이 게이트
        // 없이는 선 자세에서 초과율 100%였던 것과 같은 종류의 오류다.
        //
        // 상태머신을 돌린 **뒤에** 판단한다. 앞에서 하면 이전 프레임의 상태로 게이트해서
        // rep 시작·종료 프레임이 한 칸씩 밀린다.
        //
        // 경고가 없어도 `update`는 매 프레임 부른다 — 만료가 거기서 일어나므로, 건너뛰면
        // rep이 끝난 뒤 마지막 경고가 화면에 영원히 남는다.
        var heldWarnings = feedbackHold.update(
            warnings = if (inRep) form.warnings else emptyList(),
            side = form.kneeToeSide,
            nowMs = result.timestampMs,
        )

        // rep 구간 안이면 경고를 모은다. 선 자세·미인식 구간의 경고는 그 rep의 자세가
        // 아니므로 넣지 않는다. Completed가 뜬 프레임은 이미 STANDING으로 넘어가 있지만,
        // 그 직전 ASCENDING 프레임들이 이미 반영돼 있어 놓치는 것이 없다.
        if (inRep) {
            repWarnings += form.warnings
            // 어느 구간에서 났는지 함께 남긴다. 같은 rep에서 두 방향이 다른 구간에 나면
            // 리포트가 "내려갈 때 벌어지고, 올라올 때 모여요"라고 말할 수 있다.
            phaseOf(stateMachine.state)?.let { phase ->
                for (warning in form.warnings) {
                    repWarningPhases.getOrPut(warning) { mutableSetOf() } += phase
                }
            }
            // 게이트를 통과한 프레임만 담긴다(분석기가 이미 걸렀다) — 게이트가 닫힌
            // 프레임의 값이 섞이면 중앙값이 오염된다.
            form.kneeOverToe?.let { repKneeOverToe += it }
            // 편차의 절대값이 가장 컸던 프레임의 쪽을 남긴다.
            val side = form.kneeToeSide
            val deviation = form.kneeToeDeviation
            if (side != null && deviation != null) {
                val magnitude = abs(deviation)
                if (magnitude > (repWorstKnee?.second ?: 0f)) {
                    repWorstKnee = side to magnitude
                }
            }
        }
        form.depthRatio?.let { ratio ->
            repMaxDepthRatio = repMaxDepthRatio?.coerceAtLeast(ratio) ?: ratio
        }
        // 깊이를 지원하는 각도에서만 모은다 — 정면 값은 원근 왜곡으로 믿을 수 없다.
        if (form.cameraAngle.supports(FormCheck.DEPTH)) {
            features?.shinDepthRatio?.let { ratio ->
                repMaxShinDepth = repMaxShinDepth?.coerceAtLeast(ratio) ?: ratio
            }
        }

        // 방향 판별용 창은 **항상** 채운다. 기준 자세를 재기 전에도 어느 방향으로 서
        // 있는지 알아야 화면 문구가 맞는다([trackView]).
        readyFrames.addLast(result.pose to result.aspectRatio)
        while (readyFrames.size > READY_WINDOW_FRAMES) readyFrames.removeFirst()
        trackView()

        // 준비 자세 안내는 재는 중(PREPARING·COLLECTING)에만 갱신한다. 측정이 끝난 뒤에도
        // 계속 갱신하면 스쿼트 첫 프레임이 "발이 좁아요"로 읽힌다 — 앉기 시작하면 발목
        // 간격과 무릎 굴곡이 당연히 달라진다.
        //
        // **캘리브레이션보다 먼저 계산한다** — 3초 창을 시작할지 이 결과가 정한다.
        if (_uiState.value.calibrationStage.isMeasuring) {
            stepReadyPosture()
        }

        when (_uiState.value.calibrationStage) {
            CalibrationStage.COLLECTING -> stepCalibration(result)
            // 준비 중에는 재지 않는다. 대신 지금 서 있는 자리가 되는지만 알려준다 —
            // 카운트다운이 끝나기 전에 위치를 고칠 수 있어야 한다.
            CalibrationStage.PREPARING -> {
                val visible = StandingCalibration.REQUIRED.all {
                    result.pose.isReliable(it, StandingCalibration.MIN_CONFIDENCE)
                }
                if (visible != _uiState.value.calibrationSubjectVisible) {
                    _uiState.update { it.copy(calibrationSubjectVisible = visible) }
                }
            }
            else -> Unit
        }

        // rep 깊이는 상태와 저장이 같은 값을 써야 하므로 여기서 한 번만 계산한다.
        // `_uiState.update`는 경합 시 재실행될 수 있어 부작용을 넣으면 안 된다.
        val repDepth = repMaxDepthRatio?.let(config.form::depthLevelByRatio)
            ?: repMaxShinDepth?.let(config.form::depthLevelByShinDepth)
        val repDepthBasis = when {
            repMaxDepthRatio != null -> DepthBasis.HIP_HEIGHT
            repMaxShinDepth != null -> DepthBasis.SHIN_LENGTH
            else -> null
        }
        if (event is RepEvent.Completed) {
            // rep 중앙값으로 판정한다. 여기서 한 번 계산해 저장과 화면이 같은 값을 쓴다.
            val repKneePastToe = config.form.kneePastToeOf(repKneeOverToe.toList())
            val repLevel =
                recordCompletedRep(event.summary, repDepth, repDepthBasis, repKneePastToe)
            // **rep 단위 판정은 프레임 경고에 없다.** 화면에는 rep이 끝난 시점 —
            // 즉 **다 일어선 뒤**에 올린다. 그 시점이 `FeedbackHold`의 기본 2초가 겨냥한
            // 자리다("최저점에서 난 경고를 다 올라온 뒤에 읽을 수 있어야 한다").
            //
            // 깊이는 "방금 rep이 얕았다"이고 무릎 위치는 앉는 방식의 결과라, 둘 다 rep
            // 중간에 고칠 수 없고 **다음 rep에 쓸 지시**다.
            //
            // 저장이 쓴 것과 **같은 집합**에서 고른다. 조건을 여기서 다시 계산하면 화면과
            // 기록이 갈린다. 프레임 경고는 이미 붙잡혀 있으므로 다시 넣지 않는다 —
            // 넣으면 그 항목의 만료 시각만 밀린다.
            val repOnly = repLevel.filter { it.isRepLevel }.sortedBy { it.ordinal }
            if (repOnly.isNotEmpty()) {
                heldWarnings = feedbackHold.update(
                    warnings = repOnly,
                    side = null,
                    nowMs = result.timestampMs,
                )
            }
        }

        _uiState.update { state ->
            var next = state.copy(
                result = result,
                form = form,
                heldWarnings = heldWarnings,
                features = features,
                repState = stateMachine.state,
                analysisFps = fps,
                inferenceMillis = smoothedInferenceMillis,
            )
            next = when (event) {
                is RepEvent.Completed -> next.copy(
                    repCount = state.repCount + 1,
                    lastRep = event.summary,
                    lastRepDepth = repDepth,
                    lastRepDepthBasis = repDepthBasis,
                    lastAbortReason = null,
                )
                is RepEvent.Aborted -> next.copy(lastAbortReason = event.reason)
                is RepEvent.Started -> next.copy(lastAbortReason = null)
                null -> next
            }
            next
        }
    }

    // ── 세션 저장 ───────────────────────────────────────────

    /**
     * 완료한 rep을 저장 대기 목록에 넣는다.
     *
     * 깊이는 카운팅과 분리해 rep이 끝난 뒤 별도로 판정한다 — 설계문서 3.2절의 모순
     * (BOTTOM < 100° 이면서 깊이부족 ≥ 120°)을 피하려면 두 판단이 섞이지 않아야 한다.
     */
    private fun recordCompletedRep(
        summary: RepSummary,
        depth: DepthLevel?,
        basis: DepthBasis?,
        kneePastToe: Boolean?,
    ): Set<FormWarning> {
        // 깊이와 무릎 위치는 **rep 단위 판정이다**(`FormWarning.isRepLevel`). 분석기가
        // 프레임 경고로 내보내지 않으므로 여기서 결정된다. 판정과 항목 사이 배제 규칙은
        // `RepWarnings.of` 한 곳에 있다 — 저장과 화면이 같은 답을 써야 한다.
        val repLevelWarnings = RepWarnings.of(
            frameWarnings = repWarnings.toSet(),
            depth = depth,
            kneePastToe = kneePastToe,
        )

        // **소리는 rep이 끝날 때만 낸다.**
        //
        // 프레임 단위 경고가 뜰 때마다 울리면 한 rep에서 여러 번 난다 — 깊이·상체·무릎이
        // 각각 걸리면 세 번이다. 그리고 톤은 "무엇이" 문제인지 말할 수 없으므로 동작
        // 중간에 울려도 지금 고칠 수 있는 정보를 주지 않는다.
        //
        // rep 끝에 한 번, 대신 **세어졌다와 문제가 있었다를 한 소리에 담는다**
        // ([AudioCues.Cue.REP_WARNED]).
        cues.play(
            if (repLevelWarnings.isEmpty()) {
                AudioCues.Cue.REP_DONE
            } else {
                AudioCues.Cue.REP_WARNED
            },
        )

        completedReps += CompletedRepInput(
            repNumber = summary.repIndex,
            // rep이 방금 끝났으므로 지금 시각이 그 시각이다. summary의 타임스탬프는 프레임
            // 단조 시계라 벽시계로 환산할 기준이 없다.
            recordedAtMs = System.currentTimeMillis(),
            durationMs = summary.durationMs,
            cameraAngle = summary.cameraAngle.name,
            score = RepScorer.score(
                cameraAngle = summary.cameraAngle,
                warnings = repLevelWarnings,
                depthValue = repMaxDepthRatio ?: repMaxShinDepth,
                depthBasis = basis,
                thresholds = config.form,
            ),
            depthLevel = depth?.name,
            depthBasis = basis?.name,
            depthRatio = repMaxDepthRatio ?: repMaxShinDepth,
            minKneeAngle = summary.aggregate.minKneeAngle,
            maxTorsoLeanDegrees = summary.aggregate.maxTorsoLeanDegrees,
            validFrameRatio = summary.aggregate.validFrameRatio,
            kneeToeSide = repWorstKnee?.first?.name,
            warningPhases = repWarningPhases.mapValues { it.value.toSet() },
            // 이 rep을 판정한 기준. 값은 rep이 끝난 시점의 것이다 — 세션 도중에 설정이나
            // 노브가 바뀌면 그 뒤의 rep부터 다른 값이 남는다.
            relaxedForm = relaxedForm,
            kneeTolerance = config.form.kneeToeToleranceRatio,
            shallowTolerance = config.form.shallowToleranceRatio,
        )
        return repLevelWarnings
    }

    /**
     * 운동을 끝낸다. 종료 버튼이 부른다.
     *
     * ## 한 개도 못 셌으면 저장하지 않는다
     * 예전에는 0회도 저장했다 — "시작했지만 한 개도 못 셌다"는 것 자체가 기록이라고 봤다.
     * 그 판단을 뒤집는다(사용자 요청, 2026-08-25): **사용자에게 0회 세션은 기록이 아니라
     * 잘못 누른 흔적**이다. 카메라를 맞추다 만 세션, 각도를 바꾸다 끝낸 세션이 기록 목록과
     * 홈의 세션 수에 그대로 쌓인다.
     *
     * 잃는 것은 "왜 0회였나"를 나중에 물어볼 대상이다. 그 질문은 수집 화면(`Capture`)이
     * 답하는 자리이고, 그쪽은 프레임까지 남기므로 여기서 빈 세션을 남길 이유가 약하다.
     *
     * @param onSaved 저장했을 때 세션 id와 함께 불린다.
     * @param onDiscarded **저장하지 않고** 끝났을 때 불린다. 결과 화면으로 보내면 "세션을
     *   찾을 수 없습니다"가 뜨므로 호출부가 다른 곳으로 보내야 한다.
     */
    fun finishSession(onSaved: (String) -> Unit, onDiscarded: () -> Unit) {
        val state = _uiState.value

        if (completedReps.isEmpty()) {
            // 종료 신호는 그대로 낸다 — 버튼이 먹혔다는 것은 알려야 한다.
            cues.play(AudioCues.Cue.SESSION_END)
            onDiscarded()
            return
        }

        val samples = inferenceSamples.sorted()
        val metrics = if (analyzedFrameCount > 0) {
            SessionMetricsInput(
                modelLoadMs = state.modelLoadMillis.takeIf { it > 0 },
                avgInferenceMs = samples.average().toFloat(),
                p95InferenceMs = samples[((samples.size - 1) * 95) / 100],
                // 분모는 시도한 프레임 수다 — 성공 + 실패.
                droppedFrameRatio = state.droppedFrames.toFloat() /
                    (analyzedFrameCount + state.droppedFrames),
                avgFps = state.analysisFps.toFloat(),
                delegateType = state.activeDelegate?.name?.lowercase(),
                analyzedFrameCount = analyzedFrameCount,
            )
        } else {
            null
        }

        // 종료 신호. 사용자가 버튼을 누른 뒤 화면이 바뀌기까지 저장이 끼는데, 그 사이에
        // 아무 반응이 없으면 눌린 줄 모르고 다시 누른다.
        cues.play(AudioCues.Cue.SESSION_END)
        viewModelScope.launch {
            val id = repository.saveSession(
                startedAtMs = sessionStartedAtMs,
                endedAtMs = System.currentTimeMillis(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                poseModel = state.poseModel.label,
                reps = completedReps.toList(),
                metrics = metrics,
                // 나머지 임계값 전부. 손으로 나열하지 않는다 — data class의 문자열
                // 표현이라 임계값이 늘면 저절로 함께 남는다(`WorkoutSessionEntity`).
                formThresholds = config.form.toString(),
            )
            onSaved(id)
        }
    }

    // ── 기준 자세 ───────────────────────────────────────────

    /**
     * 기준 자세 측정을 시작한다. 이미 있으면 다시 잰다 — 카메라를 옮겼으면 기준선도 바뀐다.
     *
     * **바로 재지 않고 준비 시간을 준다.** 버튼을 누른 자리와 서야 할 자리가 다르기 때문이다
     * ([CalibrationStage.PREPARING] 참고). 준비 시간이 0이면 곧바로 수집으로 넘어간다.
     */
    fun startCalibration() {
        calibrationFrames.clear()
        calibrationStartMs = null
        readyFrames.clear()
        prepJob?.cancel()
        // 재측정을 시작하면 카운팅이 멈춘다. 진행 중이던 rep은 버린다 — 측정이 끝난 뒤
        // 이어 세면 걸어간 구간이 그 rep에 섞인다. rep 수는 유지된다.
        resetRepTracking()

        val prepMs = _uiState.value.calibrationPrepSeconds * 1_000L
        if (prepMs <= 0) {
            beginCollecting()
            return
        }

        _uiState.update {
            it.copy(
                calibrationStage = CalibrationStage.PREPARING,
                calibrationPrepRemainingMs = prepMs,
                // 지난 측정의 값이 남아 있으면 아직 화면 밖인데 "자리 좋아요"가 뜬다.
                calibrationSubjectVisible = false,
                readyPosture = ReadyPosture.EMPTY,
                calibrationRemainingMs = CALIBRATION_DURATION_MS,
                calibrationProblems = emptyList(),
                // 재측정 중에는 이전 기준선을 지운다. 옛 분모로 계산한 값이 화면에
                // 남아 있으면 새 기준선이 적용된 것처럼 보인다.
                calibration = null,
                activeSide = null,
                features = null,
            )
        }

        // 카운트다운은 프레임이 아니라 벽시계로 센다. 프레임이 끊겨도 "5초"는 5초여야 하고,
        // 준비 구간에는 사람이 화면 밖에 있어 프레임이 안 들어올 수 있다.
        prepJob = viewModelScope.launch {
            var remaining = prepMs
            var lastSecond = secondsOf(prepMs)
            while (remaining > 0) {
                delay(CalibrationPrep.TICK_MS)
                remaining -= CalibrationPrep.TICK_MS
                // 초가 바뀔 때마다 한 번. 사용자는 폰을 등지고 걸어가는 중이라 화면의
                // 숫자를 볼 수 없다 — 셀프타이머가 소리를 내는 것과 같은 이유다.
                val second = secondsOf(remaining)
                if (second < lastSecond) {
                    cues.play(AudioCues.Cue.COUNTDOWN)
                    lastSecond = second
                }
                _uiState.update {
                    it.copy(calibrationPrepRemainingMs = remaining.coerceAtLeast(0))
                }
            }
            beginCollecting()
        }
    }

    /** 준비가 끝났다. 여기서부터 실제로 프레임을 모은다. */
    /**
     * 준비가 끝났다. 여기서부터 실제로 프레임을 모은다.
     *
     * `readyFrames`는 비우지 않는다 — 준비 시간에 이미 서 있던 프레임이고, 안내가 잠깐
     * 사라졌다 돌아오면 사용자는 자기 자세가 바뀐 줄로 읽는다.
     */
    private fun beginCollecting() {
        calibrationFrames.clear()
        calibrationStartMs = null
        lastCountdownSecond = Int.MAX_VALUE
        _uiState.update {
            it.copy(
                calibrationStage = CalibrationStage.COLLECTING,
                calibrationRemainingMs = CALIBRATION_DURATION_MS,
                calibrationPrepRemainingMs = 0,
                calibrationProblems = emptyList(),
                calibration = null,
                activeSide = null,
                features = null,
            )
        }
    }

    fun cancelCalibration() {
        prepJob?.cancel()
        calibrationFrames.clear()
        calibrationStartMs = null
        readyFrames.clear()
        _uiState.update {
            it.copy(
                calibrationStage = CalibrationStage.NONE,
                readyPosture = ReadyPosture.EMPTY,
                calibrationRemainingMs = 0,
                calibrationPrepRemainingMs = 0,
                calibrationProblems = emptyList(),
            )
        }
    }

    /**
     * 캘리브레이션 창에 프레임을 모은다.
     *
     * 필수 관절이 하나라도 안 보이면 **처음부터 다시 모은다.** 조건이 어긋난 프레임이 섞인
     * 기준선은 이후 모든 특징을 조용히 틀리게 만든다 — 값이 그럴듯하게 나오므로 틀린 줄
     * 모른다. 수집 화면과 같은 규칙이다.
     */
    private fun stepCalibration(result: PoseResult) {
        val usable = StandingCalibration.REQUIRED.all {
            result.pose.isReliable(it, StandingCalibration.MIN_CONFIDENCE)
        }
        // **준비 자세를 통과해야 3초를 센다.**
        //
        // 예전에는 관절만 보였으면 3초를 세고 **끝난 뒤에** 실패를 알렸다 — 45도로 서
        // 있으면 3초를 기다린 다음 "비스듬해요"를 들었다. 기다렸다 실패하는 것이 가장
        // 나쁜 순서다. 조건이 어긋나면 창을 채우지 않고 왜 안 세는지만 보여준다.
        //
        // 막는 항목은 근거가 확실한 것만이다 — 발 간격 허용폭은 잠정값이라 안내만 한다
        // ([ReadyIssue.blocking]).
        val postureReady = _uiState.value.readyPosture.readyToMeasure
        if (!usable || !postureReady) {
            calibrationFrames.clear()
            calibrationStartMs = null
            _uiState.update { it.copy(calibrationRemainingMs = CALIBRATION_DURATION_MS) }
            return
        }

        val start = calibrationStartMs ?: result.timestampMs.also { calibrationStartMs = it }
        calibrationFrames += result.pose to result.aspectRatio
        val remaining = (CALIBRATION_DURATION_MS - (result.timestampMs - start))
            .coerceAtLeast(0)

        // **줄어들 때만 울린다.** 창이 리셋되면 남은 시간이 3초로 되돌아가는데(자세가
        // 어긋났거나 관절이 안 보였을 때) 그때 울리면 자세가 경계에서 흔들릴 때마다
        // 소리가 난다.
        //
        // 그래서 **소리가 안 나는 것이 "아직 안 세고 있다"는 신호**가 된다. 자세를 맞추면
        // 틱이 시작되고, 그게 화면을 못 읽는 거리에서 유일하게 알 수 있는 방법이다.
        val second = secondsOf(remaining)
        if (second < lastCountdownSecond) {
            cues.play(AudioCues.Cue.COUNTDOWN)
        }
        lastCountdownSecond = second

        _uiState.update { it.copy(calibrationRemainingMs = remaining) }
        if (remaining > 0) return

        finishCalibration()
    }

    /**
     * 준비 자세 안내를 갱신한다.
     *
     * 창을 굴리며 매 프레임 다시 계산한다. 프레임당 비용은 관절 몇 개의 거리 계산이고,
     * 이미 프레임마다 특징을 전부 계산하고 있으므로 추론 지연에 영향을 주지 않는다.
     */
    private fun stepReadyPosture() {
        val posture = ReadyPosture.from(
            frames = readyFrames.toList(),
            cameraAngle = _uiState.value.cameraAngle,
            form = config.form,
            ready = config.ready,
        )
        _uiState.update { it.copy(readyPosture = posture) }
    }

    /**
     * 카운팅이 시작되기 전까지는 촬영 방향을 실시간으로 따라간다.
     *
     * ## 왜 필요한가
     * 방향을 사용자가 고르지 않으므로, 기준 자세를 재기 전의 기본값은 아무 근거가 없다.
     * 그 상태로 판정하면 옆으로 선 사람에게 정면 문구가 나가고, 준비 자세 안내도 정면
     * 전용 항목을 엉뚱하게 켠다.
     *
     * ## 왜 카운팅이 시작되면 멈추는가
     * 기준선은 **그 방향에서 잰 값**이다(활성측·분모·원근 조건이 모두 다르다). rep을 세는
     * 동안 방향이 바뀌면 기준선과 판정이 어긋나므로, 확정 뒤에는 따라가지 않고 안내만 한다
     * ([SquatForm.suggestedAngle]).
     *
     * ## 왜 프레임 하나로 바꾸지 않는가
     * 창의 다수결을 쓴다. 프레임 단위 추정은 실측에서 0.5%가 반대로 읽혀 화면 문구가
     * 깜빡인다.
     */
    private fun trackView() {
        if (angleLockedByUser) return
        if (_uiState.value.countingEnabled) return
        val detected = viewOf(readyFrames.toList()) ?: return
        if (detected == _uiState.value.cameraAngle) return

        // 방향이 바뀌면 판정 항목이 통째로 갈린다. 이전 방향에서 붙잡아 둔 피드백은
        // 지금 방향에서는 재지도 않는 항목일 수 있다.
        feedbackHold.reset()
        _uiState.update { it.copy(cameraAngle = detected, heldWarnings = emptyList()) }
    }

    private fun finishCalibration() {
        val frames = calibrationFrames.toList()
        val calibration = StandingCalibration.from(frames)
        if (calibration == null) {
            failCalibration(listOf(StandingCalibration.Problem.TOO_FEW_FRAMES))
            return
        }
        val validation = calibration.validate()
        if (!validation.passed) {
            failCalibration(validation.problems)
            return
        }

        // **촬영 방향을 여기서 확정한다.** 사용자가 고르지 않는다.
        //
        // 프레임 하나로는 정하면 안 된다 — 실측에서 정면·측면 각각 0.5%가 반대로 읽히고
        // 그게 두 세션에 몰려 있다. 창 전체의 다수결은 15/15 전부 맞았다
        // (`CaptureQuality.estimateView` 주석의 표).
        // 수동으로 고른 각도는 덮어쓰지 않는다(개발 화면).
        val detected = if (angleLockedByUser) _uiState.value.cameraAngle else viewOf(frames)
        if (detected == null) {
            // 비스듬하게 서 있거나 어깨가 안 보인다. 어느 판정을 켤지 정할 수 없으므로
            // 진행하지 않는다 — 방향을 모르는 채로 재면 그 기준선이 어느 각도의 것인지도
            // 알 수 없다.
            failCalibration(listOf(StandingCalibration.Problem.AMBIGUOUS_VIEW))
            return
        }

        // 창 전체로 다시 계산한다. 실시간 안내는 최근 몇 프레임만 보므로, 결과로 남길
        // 값은 캘리브레이션 창 전체에서 낸 쪽이 안정적이다 — 프레임이 많을수록 중앙값이
        // 흔들리지 않는다.
        val readyPosture = ReadyPosture.from(
            frames = frames,
            cameraAngle = detected,
            form = config.form,
            ready = config.ready,
        )

        calibrationFrames.clear()
        calibrationStartMs = null
        readyFrames.clear()
        // 여기서부터 센다. 측정 창의 상태가 상태머신에 남아 있으면 첫 프레임이 하강 중으로
        // 읽힐 수 있어 IDLE에서 시작한다.
        //
        // **스무딩은 초기화하지 않는다** — 측정 창 3초 동안 EMA와 중앙값 창이 선 자세로
        // 채워져 있고, 그게 상태머신이 STANDING으로 들어가는 데 필요한 값이다. 여기서
        // 비우면 첫 rep이 창 크기만큼(약 0.25초) 늦게 잡힌다.
        startCounting()
        // 여기서부터 센다는 것을 소리로 알린다. 이 신호를 못 들으면 사용자는 자기가
        // 스쿼트를 시작해도 되는지 알 수 없다 — 화면의 문구는 그 거리에서 안 읽힌다.
        cues.play(AudioCues.Cue.CALIBRATED)
        // 소리가 실제로 중요해지는 순간이다. 여기서 다시 확인한다 — 화면에 들어온 뒤
        // 볼륨을 내렸을 수도 있다.
        refreshSoundMuted()
        _uiState.update {
            it.copy(
                calibrationStage = CalibrationStage.READY,
                calibration = calibration,
                readyPosture = readyPosture,
                cameraAngle = detected,
                // 정면에서는 좌우를 모두 쓰므로 고정하지 않는다.
                activeSide = if (detected == CameraAngle.SIDE) {
                    StandingCalibration.nearerSide(frames)
                } else {
                    null
                },
                calibrationRemainingMs = 0,
                // 차단하지 않는 문제(발 미검출)는 남겨서 어떤 특징이 빠지는지 알린다.
                calibrationProblems = validation.problems,
            )
        }
    }

    private fun failCalibration(problems: List<StandingCalibration.Problem>) {
        cues.play(AudioCues.Cue.FAILED)
        calibrationFrames.clear()
        calibrationStartMs = null
        readyFrames.clear()
        _uiState.update {
            it.copy(
                calibrationStage = CalibrationStage.NONE,
                // 실패 사유와 준비 자세 안내가 함께 뜨면 무엇을 고쳐야 할지 흐려진다.
                readyPosture = ReadyPosture.EMPTY,
                calibrationProblems = problems,
                calibrationRemainingMs = 0,
            )
        }
    }

    /**
     * 프레임 창에서 촬영 방향을 정한다. 애매하거나 추정 불가면 null이다.
     *
     * 판별 계산은 [CaptureQuality]가 한다 — 경계값을 아는 것은 엔진 쪽 일이고, 예전에
     * 같은 계산이 두 곳에 있어 경계가 갈라진 적이 있다.
     */
    private fun viewOf(frames: List<Pair<Pose, Float>>): CameraAngle? =
        when (CaptureQuality.estimateView(frames, config.angleDetection)) {
            CaptureQuality.EstimatedView.FRONT -> CameraAngle.FRONT
            CaptureQuality.EstimatedView.SIDE -> CameraAngle.SIDE
            CaptureQuality.EstimatedView.AMBIGUOUS, null -> null
        }

    /**
     * 현재 프레임 특징.
     *
     * 기준선이 있으면 거리 특징(깊이 비율·엉덩이 하강·뒤꿈치 들림)까지 나오고, 없으면
     * **발목 간격으로 정규화하는 특징만** 나온다 — 골반 쏠림과 무릎–발끝 정렬이 그렇다.
     * 그 둘은 기준선이 필요 없으므로 캘리브레이션을 안 했다고 판정을 막지 않는다.
     */
    private fun featuresOf(result: PoseResult): FrameFeatures? {
        val state = _uiState.value
        // 기준선은 없어도 된다 — 발목 간격으로 정규화하는 특징(골반 쏠림, 무릎–발끝 정렬)은
        // 기준선이 필요 없다. 거리 특징만 null이 된다.
        val view = state.captureView ?: return null
        return FrameFeatures.from(
            pose = result.pose,
            aspectRatio = result.aspectRatio,
            calibration = state.calibration,
            view = view,
            activeSide = view.activeSide,
        )
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
            kneeSpreadRatio = form.kneeSpreadRatio,
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

    /**
     * 카운팅을 처음부터 시작한다. 스무딩은 유지한다.
     *
     * [resetRepTracking]과 나눈 이유: 저쪽은 시계열이 **끊겼을 때**(카메라·모델 전환) 쓰는
     * 것이라 스무딩까지 버려야 하지만, 기준 자세 측정 직후는 시계열이 이어져 있다.
     */
    private fun startCounting() {
        stateMachine.reset()
        repMaxDepthRatio = null
        repMaxShinDepth = null
        repWarnings.clear()
        repKneeOverToe.clear()
        repWorstKnee = null
        repWarningPhases.clear()
    }

    /** 카메라·모델 전환처럼 시계열이 끊기는 경우 스무딩과 상태머신을 초기화한다. */
    private fun resetRepTracking() {
        stateMachine.reset()
        // 시계열이 끊기면 붙잡아 둔 경고도 버린다 — 카메라를 바꿨다면 그 경고는 이미
        // 다른 화면을 보고 낸 판정이다.
        feedbackHold.reset()
        kneeEma.reset()
        kneeMedian.reset()
        smoothedFrameIntervalMs = 0.0
        lastFrameTimestampMs = null
        // 진행 중이던 rep의 깊이를 다음 rep으로 흘리지 않는다.
        repMaxDepthRatio = null
        repMaxShinDepth = null
        repKneeOverToe.clear()
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
                heldWarnings = emptyList(),
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
    /**
     * 촬영 각도를 바꾼다.
     *
     * 기준선을 무효화한다 — 활성측은 그 각도에서 정한 값이고, 정면·측면은 원근 조건이
     * 달라 같은 분모를 쓸 수 없다. 각도를 바꿨으면 다시 재야 한다.
     */
    fun selectCameraAngle(angle: CameraAngle) {
        if (_uiState.value.cameraAngle == angle) return
        // 직접 골랐으면 자동 판별을 끈다. 켜둔 채로 두면 다음 프레임에 덮어써진다.
        angleLockedByUser = true
        resetRepTracking()
        calibrationFrames.clear()
        calibrationStartMs = null
        readyFrames.clear()
        _uiState.update {
            it.copy(
                cameraAngle = angle,
                form = null,
                // 각도가 바뀌면 판정 항목이 갈린다. 이전 각도의 경고를 남겨두면 지금
                // 각도에서는 재지도 않는 항목을 지적한다.
                heldWarnings = emptyList(),
                repState = RepState.IDLE,
                calibrationStage = CalibrationStage.NONE,
                calibration = null,
                // 준비 자세 항목의 절반이 정면 전용이다. 각도가 바뀌면 판정 가능 항목도
                // 바뀌므로 이전 결과를 남겨두면 안 된다.
                readyPosture = ReadyPosture.EMPTY,
                activeSide = null,
                features = null,
                calibrationProblems = emptyList(),
                calibrationRemainingMs = 0,
            )
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

    /**
     * 미디어 볼륨이 0인지 다시 본다.
     *
     * 프레임마다 보지 않는다 — 오디오 서비스 조회를 30fps로 하는 것은 낭비고, 볼륨은
     * 사용자가 버튼을 눌러야 바뀐다. 소리가 중요해지는 시점에만 확인한다.
     */
    private fun refreshSoundMuted() {
        val audio = getApplication<Application>()
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val muted = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        if (muted != _uiState.value.soundMuted) {
            _uiState.update { it.copy(soundMuted = muted) }
        }
    }

    override fun onCleared() {
        prepJob?.cancel()
        cues.release()
        super.onCleared()
        engine?.close()
        engine = null
    }

    private companion object {
        /**
         * 상태머신 상태를 리포트가 쓰는 구간으로 옮긴다. rep 밖이면 null이다.
         *
         * "rep 안인가"는 [RepState.isInRep]가 답한다 — 예전에는 이 함수의 null 분기와
         * 별도의 `REP_PHASES` 집합이 같은 사실을 두 번 적고 있었다.
         */
        private fun phaseOf(state: RepState): RepPhase? = when (state) {
            RepState.DESCENDING -> RepPhase.DESCENDING
            RepState.BOTTOM -> RepPhase.BOTTOM
            RepState.ASCENDING -> RepPhase.ASCENDING
            RepState.IDLE, RepState.STANDING -> null
        }

        /**
         * 선 자세 유지 시간(ms). 수집 화면과 같은 값이다.
         *
         * 3초면 30fps에서 90프레임 남짓이라 `MIN_FRAMES`(20)에 여유가 있고, 사용자가
         * 가만히 서 있기를 요구하는 시간으로도 짧다.
         */
        const val CALIBRATION_DURATION_MS = 3_000L

        /**
         * 준비 자세 안내에 쓰는 창 크기(프레임).
         *
         * 30fps에서 약 0.5초다. 중앙값을 낼 만큼 크고(실측 P009처럼 몇 프레임이 무너져도
         * 흡수한다), 사용자가 발을 옮겼을 때 반영이 늦다고 느끼지 않을 만큼 작아야 한다.
         * 캘리브레이션 창(3초)을 그대로 쓰면 이미 고친 자세를 계속 지적한다.
         */
        const val READY_WINDOW_FRAMES = 15

        /** 남은 ms를 올림한 초. 화면 표시와 같은 규칙이라 소리와 숫자가 어긋나지 않는다. */
        fun secondsOf(remainingMs: Long): Int = ((remainingMs + 999) / 1000).toInt()


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
