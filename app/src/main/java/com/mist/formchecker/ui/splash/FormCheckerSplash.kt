package com.mist.formchecker.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.OutlineVariant
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * 인앱 스플래시 — 시스템 스플래시가 사라진 **직후**에 재생하는 관절 교정 모션.
 *
 * ## 무엇을 보여주는가
 * `잘못된 자세 감지(오렌지) → 관절 정렬 → 라임 체크 완성`. 앱이 하는 일 자체가 세 줄로
 * 요약된 장면이고, 브랜드 심볼(`Joint Check`)이 어디서 왔는지를 말한다.
 *
 * **오렌지는 시작 상태에만 쓴다**(브랜드 규칙). 정렬이 끝난 최종 상태와 성공은 라임이다 —
 * 앱 안에서 [FeedbackWarning]이 "고쳐야 할 것", [LimeGreen]이 "된 것"인 규칙과 같다.
 *
 * ## 왜 시스템 스플래시와 나눠 맡는가
 * 시스템 스플래시(`Theme.FormChecker.Starting`)는 **움직이지 않는 심볼**만 그린다.
 * 아이콘 애니메이션은 최대 1초로 제한되고 기기·OS 버전마다 잘리는 방식이 달라, 브랜드
 * 모션을 거기에 맡기면 사람마다 다른 것을 본다. 두 창의 배경색이 같아
 * (`@color/formchecker_bg_base` == [BgBase]) 갈아타는 순간이 보이지 않는다.
 *
 * ## 여기서 무거운 일을 하지 않는다
 * 이전 `SplashScreen`의 주석이 설계문서 8장(콜드스타트 측정)을 근거로 **모델 프리로드를
 * 메인스레드에서 하지 말 것**을 남겨뒀다. 그 자리는 아직 비어 있다 — 프리로드를 붙일
 * 때도 이 화면은 표시만 하고, 로딩은 ViewModel/백그라운드로 보낸다.
 *
 * @param onFinished 모션이 끝났을 때 한 번만 불린다. 화면 회전·프로세스 재생성으로
 *   모션을 두 번 재생하지 않도록 [rememberSaveable]로 완료 여부를 들고 있다.
 */
@Composable
fun FormCheckerSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 회전이나 프로세스 재생성으로 이 컴포저블이 다시 만들어져도 모션을 처음부터 다시
    // 재생하지 않는다. 저장되는 것은 "끝났는가" 한 비트뿐이다.
    var finished by rememberSaveable { mutableStateOf(false) }
    // 콜백이 바뀌어도 아래 이펙트를 다시 시작시키지 않는다 — 재시작하면 모션이 다시 돈다.
    val currentOnFinished by rememberUpdatedState(onFinished)

    val reduceMotion = rememberReduceMotion()
    val alignment = remember { Animatable(if (finished || reduceMotion) 1f else 0f) }
    val pulse = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (!finished) {
            if (reduceMotion) {
                // 접근성: 애니메이션이 꺼진 기기에서는 교정 모션을 생략하고 최종 상태만
                // 보여준다. 그래도 한 박자는 머문다 — 심볼이 뜨자마자 사라지면 그것대로
                // 깜빡임이 된다.
                delay(FinalHoldMs)
            } else {
                delay(StartDelayMs)
                alignment.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = AlignDurationMs,
                        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
                    ),
                )
                // 정렬이 맞은 순간의 짧은 확인 신호. 앱 안에서 rep이 인정될 때 나는
                // 소리와 같은 자리의 표현이다.
                pulse.animateTo(1.15f, tween(durationMillis = PulseMs))
                pulse.animateTo(1f, tween(durationMillis = PulseMs))
                delay(FinalHoldMs)
            }
            finished = true
        }
        currentOnFinished()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        JointCheckMotion(
            progress = alignment.value,
            scale = pulse.value,
            modifier = Modifier
                .size(SymbolSize)
                // 그림만 있는 장면이라 이름을 붙인다. 로고 자체가 화면의 내용이다.
                .semantics { contentDescription = "폼체커" },
        )
        Text(
            text = "FORMCHECKER",
            color = TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "바른 움직임의 시작",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/**
 * 기기에서 애니메이션이 꺼져 있는가(개발자 옵션 또는 접근성 "애니메이션 제거").
 *
 * Compose에 감소 모션을 직접 알려주는 API가 없어 플랫폼 설정을 읽는다. 이 값은 앱이 살아
 * 있는 동안 바뀌어도 스플래시에는 의미가 없으므로 한 번만 읽는다.
 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * 관절점 3개가 흐트러진 자리에서 체크 모양으로 옮겨 붙는 장면.
 *
 * 좌표는 심볼(`ic_formchecker_symbol`)의 최종 배치를 비율로 옮긴 것이다 — 끝 상태가
 * 런처 아이콘과 같은 모양이어야 하나의 로고로 읽힌다.
 */
@Composable
private fun JointCheckMotion(
    progress: Float,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        fun mix(start: Offset, end: Offset) = Offset(
            x = start.x + (end.x - start.x) * progress,
            y = start.y + (end.y - start.y) * progress,
        )

        val w = size.width
        val h = size.height
        // 시작: 무너진 자세(무릎이 안으로 말리고 상체가 앞으로 쏠린 배치).
        val start = listOf(
            Offset(w * 0.30f, h * 0.27f),
            Offset(w * 0.58f, h * 0.49f),
            Offset(w * 0.42f, h * 0.75f),
        )
        // 끝: 심볼의 관절점 3개.
        val end = listOf(
            Offset(w * 0.24f, h * 0.56f),
            Offset(w * 0.45f, h * 0.69f),
            Offset(w * 0.80f, h * 0.31f),
        )
        val points = start.zip(end).map { (from, to) -> mix(from, to) }
        // 색은 정렬이 절반 넘게 진행된 뒤부터 넘어간다 — 시작하자마자 라임이 되면
        // "고쳤다"는 장면이 아니라 그냥 색이 변하는 장면이 된다.
        val resolved = ((progress - 0.42f) / 0.58f).coerceIn(0f, 1f)
        val jointColor = lerp(FeedbackWarning, LimeGreen, resolved)
        val guideAlpha = (1f - progress).coerceIn(0f, 1f)
        val stroke = 11.dp.toPx() * scale
        val radius = 15.dp.toPx() * scale

        // 기준선. 정렬이 끝나면 사라진다.
        drawLine(
            color = OutlineVariant.copy(alpha = guideAlpha * 0.9f),
            start = Offset(w * 0.5f, h * 0.12f),
            end = Offset(w * 0.5f, h * 0.86f),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
        )
        drawLine(jointColor, points[0], points[1], stroke, cap = StrokeCap.Round)
        drawLine(jointColor, points[1], points[2], stroke, cap = StrokeCap.Round)
        points.forEachIndexed { index, point ->
            drawCircle(
                color = jointColor,
                radius = if (index == 1) radius * 0.9f else radius,
                center = point,
            )
        }
    }
}

private val SymbolSize = 184.dp

/** 첫 프레임이 그려지고 눈이 따라오기까지의 여유. */
private const val StartDelayMs = 120L
private const val AlignDurationMs = 640
private const val PulseMs = 90
/** 완성된 체크를 읽을 시간. 이만큼은 홈으로 넘어가지 않는다. */
private const val FinalHoldMs = 240L
