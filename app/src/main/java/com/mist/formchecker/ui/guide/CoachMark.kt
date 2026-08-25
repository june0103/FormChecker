package com.mist.formchecker.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Scrim
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceCard
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget
import kotlin.math.roundToInt

/**
 * 코치마크 한 단계.
 *
 * @property anchor 강조할 대상. 화면이 [coachAnchor]로 등록한 키와 같아야 한다.
 *   **좌표를 여기 적지 않는다** — 고정 좌표는 글자 크기·화면 크기·스크롤에 따라 바로 어긋난다.
 * @property note 부가 설명. 없으면 그리지 않는다.
 */
@Immutable
data class CoachStep(
    val anchor: Any,
    val title: String,
    val body: String,
    val note: String? = null,
)

/**
 * 강조 대상들의 실제 화면 좌표와 현재 단계를 들고 있는 상태.
 *
 * 좌표는 [coachAnchor]가 `onGloballyPositioned`로 채운다. 조건부로 렌더링되는 대상(기록이
 * 없으면 없는 카드 등)은 등록되지 않으므로 그 단계는 자동으로 빠진다([visibleSteps]).
 */
@Stable
class CoachMarkState internal constructor(initialStep: Int) {

    internal val bounds = mutableStateMapOf<Any, Rect>()
    private val requesters = mutableStateMapOf<Any, BringIntoViewRequester>()

    /** 회전·구성 변경으로 다시 만들어져도 이어서 볼 수 있게 밖에서 복원해 준다. */
    var stepIndex by mutableIntStateOf(initialStep)
        internal set

    /** 사용자가 닫았나(건너뛰기·완료·뒤로가기). 화면이 다시 띄우지 않게 한다. */
    var dismissed by mutableStateOf(false)
        internal set

    /**
     * 처음 단계부터 다시 보여준다.
     *
     * 설정에서 `지금 다시 보기`를 골랐을 때 쓴다 — 이미 이 화면에서 안내를 닫은 뒤라면
     * [dismissed]가 켜져 있어, 저장된 완료 상태만 되돌려서는 다시 뜨지 않는다.
     */
    fun restart() {
        stepIndex = 0
        dismissed = false
    }

    internal fun register(key: Any, requester: BringIntoViewRequester) {
        requesters[key] = requester
    }

    internal fun unregister(key: Any) {
        requesters.remove(key)
        bounds.remove(key)
    }

    internal fun put(key: Any, rect: Rect) {
        bounds[key] = rect
    }

    internal fun requester(key: Any): BringIntoViewRequester? = requesters[key]
}

@Composable
fun rememberCoachMarkState(): CoachMarkState {
    // 단계 번호만 저장한다. 좌표는 다시 측정되는 값이라 저장하면 오히려 낡는다 —
    // 회전하면 레이아웃이 달라져서 저장해 둔 좌표는 어차피 틀린다.
    val savedStep = rememberSaveable { mutableIntStateOf(0) }
    val state = remember { CoachMarkState(savedStep.intValue) }
    // 합성이 끝난 뒤에 옮겨 담는다. 합성 도중에 쓰면 방금 읽은 값을 다시 무효화해
    // 한 번 더 그리게 된다.
    SideEffect { savedStep.intValue = state.stepIndex }
    return state
}

/**
 * 이 컴포넌트를 코치마크 대상으로 등록한다.
 *
 * `onGloballyPositioned`로 **실제 배치된 자리**를 받는다. 스크롤 아래에 있으면
 * [BringIntoViewRequester]가 먼저 화면 안으로 끌어온 뒤 강조한다.
 */
fun Modifier.coachAnchor(key: Any, state: CoachMarkState): Modifier = composed {
    val requester = remember(key) { BringIntoViewRequester() }
    DisposableEffect(key) {
        state.register(key, requester)
        onDispose { state.unregister(key) }
    }
    this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { state.put(key, it.boundsInRoot()) }
}

/**
 * 지금 그릴 수 있는 단계만 고른다. **대상이 없는 단계는 건너뛴다.**
 *
 * 결과 화면의 `동작별 기록`처럼 데이터가 있어야 나타나는 대상이 있다. 그 단계를 그대로
 * 두면 아무것도 강조하지 않은 채 설명만 뜬다.
 */
fun visibleSteps(steps: List<CoachStep>, anchors: Set<Any>): List<CoachStep> =
    steps.filter { it.anchor in anchors }

/**
 * 실제 화면 위에 스포트라이트와 설명 카드를 얹는다.
 *
 * ## 왜 별도 설명 화면이 아닌가
 * "이 버튼이 그 버튼이다"를 말하려면 **그 버튼 자리**를 가리켜야 한다. 그림으로 옮겨 그린
 * 설명 화면은 화면이 바뀔 때마다 조용히 낡는다.
 *
 * ## 입력을 막는다
 * 스크림이 탭을 삼킨다 — 안내를 읽는 중에 아래 버튼(삭제·카메라 전환·운동 종료)이 실제로
 * 눌리면 안 된다. 시스템 뒤로가기는 **닫기**로 연결하되 완료 처리하지 않는다.
 *
 * @param onFinish 끝까지 봤거나 건너뛰었을 때. 여기서만 "봤다"를 저장한다.
 * @param onDismiss 도중에 닫혔을 때(뒤로가기). 저장하지 않는다.
 */
@Composable
fun CoachMarkOverlay(
    state: CoachMarkState,
    steps: List<CoachStep>,
    onFinish: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val shown = visibleSteps(steps, state.bounds.keys)
    if (shown.isEmpty() || state.dismissed) return

    val index = state.stepIndex.coerceIn(0, shown.lastIndex)
    val step = shown[index]
    val target = state.bounds[step.anchor] ?: return

    // 스크롤 아래에 있으면 먼저 끌어온다. 좌표는 그 뒤에 다시 들어온다.
    LaunchedEffect(step.anchor) { state.requester(step.anchor)?.bringIntoView() }

    // ## 좌표계를 하나로 맞춘다
    // 강조 좌표는 `boundsInRoot`(화면 루트 기준)인데, 이 오버레이는 Scaffold 안쪽에
    // 놓이므로 원점이 상태바만큼 내려가 있다. **자기 원점을 재서 빼면** 두 좌표계가 맞는다.
    //
    // 예전에는 `Popup`으로 띄웠는데, 팝업 창이 시스템 바만큼 안쪽으로 잡혀(실측 높이
    // 2823 vs 창 3120) 스포트라이트가 그만큼 어긋났다. 창을 따로 띄우지 않으면 그 차이가
    // 생기지 않는다.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val local = Rect(
        left = target.left - origin.x,
        top = target.top - origin.y,
        right = target.right - origin.x,
        bottom = target.bottom - origin.y,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            // 아래로 새는 탭을 막는다.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        BackHandler {
            state.dismissed = true
            onDismiss()
        }

        Spotlight(local)

        CoachCard(
            step = step,
            index = index,
            total = shown.size,
            target = local,
            onPrevious = { state.stepIndex = (index - 1).coerceAtLeast(0) },
            onNext = {
                if (index == shown.lastIndex) {
                    state.dismissed = true
                    onFinish()
                } else {
                    state.stepIndex = index + 1
                }
            },
            onSkip = {
                state.dismissed = true
                onFinish()
            },
        )
    }
}

/** 대상만 남기고 어둡게 덮는다. 테두리를 함께 그려 색이 아니라 형태로도 대상이 드러나게 한다. */
@Composable
private fun Spotlight(target: Rect) {
    val density = LocalDensity.current
    val pad = with(density) { Spacing.xs.toPx() }
    val corner = with(density) { Radius.md.toPx() }

    Canvas(
        // 스크림은 장식이라 스크린 리더가 읽을 것이 없다.
        modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
    ) {
        val hole = RoundRect(
            rect = Rect(
                left = (target.left - pad).coerceAtLeast(0f),
                top = (target.top - pad).coerceAtLeast(0f),
                right = (target.right + pad).coerceAtMost(size.width),
                bottom = (target.bottom + pad).coerceAtMost(size.height),
            ),
            cornerRadius = CornerRadius(corner, corner),
        )
        val path = Path().apply { addRoundRect(hole) }

        clipPath(path, clipOp = ClipOp.Difference) {
            drawRect(color = Scrim.copy(alpha = ScrimAlpha), size = size)
        }
        drawPath(
            path = path,
            color = LimeGreen,
            style = Stroke(width = with(density) { 2.dp.toPx() }),
        )
    }
}

/**
 * 설명 카드. 대상 아래에 놓고, 자리가 없으면 위에 놓는다.
 *
 * 위치를 픽셀로 계산하지 않고 **대상 위/아래 중 넓은 쪽**만 고른다 — 글자가 커지면 카드
 * 높이가 달라지므로 정확한 좌표를 미리 알 수 없다.
 */
@Composable
private fun CoachCard(
    step: CoachStep,
    index: Int,
    total: Int,
    target: Rect,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val gap = with(density) { Spacing.md.toPx() }
        // 이 상자는 이미 안전 영역 안이다(오버레이가 화면 콘텐츠 자리에 놓인다).
        val below = maxHeightPx - target.bottom - gap
        val above = target.top - gap

        // **높이를 아직 모를 때(첫 배치) 넓은 쪽을 고른다.** 0과 비교하면 무조건
        // "아래에 들어간다"가 되어, 아래 공간이 없는 대상에서 카드가 대상을 덮는다.
        val putBelow = when {
            cardHeight > 0 && below >= cardHeight -> true
            cardHeight > 0 && above >= cardHeight -> false
            else -> below >= above
        }

        val topPx = if (putBelow) {
            (target.bottom + gap).coerceAtMost((maxHeightPx - cardHeight).coerceAtLeast(0f))
        } else {
            (target.top - gap - cardHeight).coerceAtLeast(0f)
        }

        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.md)
                .offset { IntOffset(0, topPx.roundToInt()) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(SurfaceCard)
                .padding(Spacing.md)
                .onGloballyPositioned { cardHeight = it.size.height }
                // 설명이 강조 대상보다 **먼저** 읽혀야 한다. 대상만 먼저 읽히면
                // 무엇을 보라는 것인지 모른 채 버튼 이름만 듣는다.
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = -1f
                },
        ) {
            Text(
                text = "${index + 1} / $total",
                style = MaterialTheme.typography.labelSmall,
                color = LimeGreen,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            step.note?.let { note ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.heightIn(min = TouchTarget.minSize),
                ) {
                    Text("건너뛰기", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                }
                Spacer(Modifier.weight(1f))
                if (index > 0) {
                    TextButton(
                        onClick = onPrevious,
                        modifier = Modifier.heightIn(min = TouchTarget.minSize),
                    ) {
                        Text("이전", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick = onNext,
                    modifier = Modifier.heightIn(min = TouchTarget.minSize),
                ) {
                    Text(
                        text = if (index == total - 1) "확인" else "다음",
                        style = MaterialTheme.typography.labelLarge,
                        color = LimeGreen,
                    )
                }
            }
        }
    }
}

private const val ScrimAlpha = 0.82f
