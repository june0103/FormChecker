package com.mist.formchecker.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mist.formchecker.R
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceVariant
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget
import kotlinx.coroutines.launch

/**
 * 설치 후 첫 실행에 한 번 보여주는 4쪽 안내.
 *
 * ## 무엇을 말하고 무엇을 말하지 않나
 * **지금 앱이 실제로 하는 것만** 말한다 — 횟수 세기, 자세 안내, 하루 목표와 주간 흐름,
 * 기준 자세를 재고 나서 세기 시작한다는 순서, 촬영 환경. 점수·등급을 준다고 하지 않고,
 * 트레이너나 의료 전문가를 대신한다고 하지 않는다.
 *
 * ## 여기서 카메라 권한을 묻지 않는다
 * 권한은 카메라를 실제로 켜는 자리(운동 화면)에서 묻는 것이 맞다. 설명만 읽는 화면에서
 * 물으면 무엇에 쓰는지 모른 채 거절하게 된다.
 *
 * ## 그림은 브랜드 요소와 도형으로만
 * 새 이미지를 만들거나 내려받지 않는다. 1쪽은 앱 심볼이고, 나머지는 화면에서 실제로 보게
 * 될 것(막대그래프·정렬되는 관절점·촬영 구도)을 [Canvas] 도형으로 옮겼다.
 *
 * ## 다시 보기도 같은 화면이다
 * 설정의 도움말에서 **지금 보기**로 열면 [review]가 켜진다. 내용은 같고, 끝났을 때
 * 홈으로 밀어내지 않고 온 자리로 돌아가는 것만 다르다 — 그리고 완료 상태를 바꾸지 않는다.
 *
 * @param review 다시 보기로 열었나. 버튼 이름이 `시작하기`가 아니라 `닫기`가 된다 —
 *   이미 쓰고 있는 사람에게 "시작"은 무엇이 시작되는지 알 수 없는 말이다.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    review: Boolean = false,
) {
    val pages = OnboardingPages
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    // 뒤로가기는 한 쪽씩 되돌아간다. 첫 쪽에서는 시스템 기본 동작(앱 종료)에 맡긴다 —
    // 여기서 홈으로 보내면 온보딩을 "끝낸" 것이 되어 다음 실행에 다시 뜨지 않는다.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = modifier.fillMaxSize().background(BgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onFinish,
                modifier = Modifier.heightIn(min = TouchTarget.minSize),
            ) {
                Text(
                    text = if (review) "닫기" else "건너뛰기",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMuted,
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { index ->
            OnboardingPageContent(pages[index])
        }

        PageIndicator(
            count = pages.size,
            current = pagerState.currentPage,
            modifier = Modifier.padding(vertical = Spacing.md),
        )

        Button(
            onClick = {
                if (pagerState.currentPage == pages.lastIndex) {
                    onFinish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.lg)
                .heightIn(min = ActionHeight),
        ) {
            Text(
                text = when {
                    pagerState.currentPage < pages.lastIndex -> "다음"
                    review -> "닫기"
                    else -> "시작하기"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 큰 글자 설정에서 본문이 화면을 넘는다. 넘치면 스크롤한다.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnboardingArt(page.art)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(Spacing.md))
        page.body.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                if (page.body.size > 1) {
                    // 색만으로 목록을 표시하지 않는다 — 글머리 기호가 함께 있어야
                    // 흑백이나 색각 이상에서도 항목으로 읽힌다.
                    Text("·  ", style = MaterialTheme.typography.bodyMedium, color = LimeGreen)
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
            }
        }
        page.note?.let { note ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** 몇 쪽 중 몇 쪽인가. 색만으로 구분하지 않도록 현재 칸은 **길이가 다르다.** */
@Composable
private fun PageIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${current + 1}번째 쪽, 전체 ${count}쪽" },
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.xs)
                    .height(DotSize)
                    .width(if (active) DotSize * 3 else DotSize)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(if (active) LimeGreen else SurfaceVariant),
            )
        }
    }
}

/**
 * 쪽마다 다른 그림. **새 이미지를 만들지 않는다** — 앱 심볼과 도형뿐이다.
 */
@Composable
private fun OnboardingArt(art: OnboardingArt) {
    Box(
        modifier = Modifier
            .padding(top = Spacing.lg)
            .size(ArtSize),
        contentAlignment = Alignment.Center,
    ) {
        when (art) {
            OnboardingArt.SYMBOL -> Image(
                painter = painterResource(R.drawable.ic_formchecker_symbol),
                contentDescription = null,
                modifier = Modifier.size(ArtSize),
            )

            OnboardingArt.WEEK_BARS -> Canvas(Modifier.size(ArtSize)) {
                // 홈의 주간 그래프를 축소한 모양.
                val gap = size.width * 0.06f
                val barWidth = (size.width - gap * 6) / 7
                val heights = listOf(0.35f, 0.6f, 0.15f, 0.8f, 0.45f, 0.0f, 0.55f)
                heights.forEachIndexed { index, fraction ->
                    val left = index * (barWidth + gap)
                    val height = size.height * fraction
                    drawRoundRect(
                        color = if (fraction >= 0.6f) LimeGreen else SurfaceVariant,
                        topLeft = androidx.compose.ui.geometry.Offset(left, size.height - height),
                        size = androidx.compose.ui.geometry.Size(barWidth, maxOf(height, gap)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap, gap),
                    )
                }
            }

            OnboardingArt.CALIBRATION -> Canvas(Modifier.size(ArtSize)) {
                // 곧게 선 사람 + 기준선. 기준 자세를 재는 장면이다.
                val cx = size.width / 2f
                drawLine(
                    color = SurfaceVariant,
                    start = androidx.compose.ui.geometry.Offset(cx, 0f),
                    end = androidx.compose.ui.geometry.Offset(cx, size.height),
                    strokeWidth = size.width * 0.02f,
                )
                val stroke = size.width * 0.06f
                drawCircle(LimeGreen, radius = size.width * 0.09f, center = androidx.compose.ui.geometry.Offset(cx, size.height * 0.16f))
                drawLine(
                    color = LimeGreen,
                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.28f),
                    end = androidx.compose.ui.geometry.Offset(cx, size.height * 0.62f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = LimeGreen,
                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.62f),
                    end = androidx.compose.ui.geometry.Offset(cx - size.width * 0.14f, size.height * 0.94f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = LimeGreen,
                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.62f),
                    end = androidx.compose.ui.geometry.Offset(cx + size.width * 0.14f, size.height * 0.94f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            OnboardingArt.FRAMING -> Canvas(Modifier.size(ArtSize)) {
                // 세로 화면 안에 사람이 다 들어온 구도.
                val inset = size.width * 0.18f
                drawRoundRect(
                    color = SurfaceVariant,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(inset / 2, inset / 2),
                )
                val cx = size.width / 2f
                val stroke = size.width * 0.05f
                drawCircle(LimeGreen, radius = size.width * 0.07f, center = androidx.compose.ui.geometry.Offset(cx, size.height * 0.22f))
                drawLine(
                    color = LimeGreen,
                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.32f),
                    end = androidx.compose.ui.geometry.Offset(cx, size.height * 0.6f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = LimeGreen,
                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.6f),
                    end = androidx.compose.ui.geometry.Offset(cx - size.width * 0.1f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = LimeGreen,
                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.6f),
                    end = androidx.compose.ui.geometry.Offset(cx + size.width * 0.1f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val ArtSize = 140.dp
private val DotSize = 8.dp
private val ActionHeight = 56.dp
