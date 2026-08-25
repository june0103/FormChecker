package com.mist.formchecker.ui.screen.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceVariant
import com.mist.formchecker.ui.theme.TextDisabled
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary

/**
 * 이번 주 7일 막대그래프. **막대를 누르면 그 날이 선택된다.**
 *
 * ## 왜 잔디 칸이 아닌가
 * 기여 그래프식 정사각 칸은 **색 진하기로만** 양을 말한다. 이 앱은 색으로만 상태를
 * 구분하지 않기로 했고(디자인시스템 34행), 무엇보다 "몇 회 했나"가 홈에서 가장 궁금한
 * 값이라 높이와 숫자로 함께 보여주는 쪽이 맞다.
 *
 * ## 색만으로 달성을 말하지 않는다
 * 목표를 채운 날은 라임 막대 **+ 체크 표시 + 숫자 색**이 함께 바뀐다. 오늘은 **테두리와
 * 굵은 요일 이름**으로 구분한다.
 *
 * ## 높이 정규화
 * 기준은 `max(이번 주 최대, 목표, 1)`이다. 목표를 함께 넣는 이유: 목표가 30회인데 최대가
 * 5회인 주에 최대치를 꼭대기로 그리면 **5회가 가득 찬 것처럼 보인다.** 1을 바닥에 두는
 * 것은 0으로 나누는 것을 막기 위함이다.
 *
 * 차트 라이브러리를 넣지 않는다 — 막대 7개는 Row와 Box로 충분하고, 의존성이 늘면
 * 테마 토큰 밖의 색이 따라 들어온다.
 *
 * @param selectedIndex 지금 골라 놓은 날. 카드 아래의 하루치 요약이 이 날을 말한다.
 *   기본은 오늘이고, 다른 날을 누르면 그 날로 옮겨간다.
 */
@Composable
internal fun WeekBars(
    days: List<DayReps>,
    goal: Int?,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = maxOf(days.maxOfOrNull { it.reps } ?: 0, goal ?: 0, 1)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            DayColumn(
                day = day,
                label = WeekDayLabels[index],
                fill = day.reps.toFloat() / scale,
                selected = index == selectedIndex,
                onSelect = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayColumn(
    day: DayReps,
    label: String,
    fill: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barHeight = (BarMaxHeight * fill.coerceIn(0f, 1f))
        // 1회만 해도 막대가 보여야 한다. 0회는 채우지 않는다 — 빈 트랙 자체가 상태다.
        .coerceAtLeast(if (day.reps > 0) MinVisibleBar else 0.dp)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            // 고른 날은 기둥 전체가 옅은 판 위에 올라간다. 오늘 표시(테두리)와 겹쳐도
            // 서로 다른 신호로 읽히도록 자리를 나눴다 — 판은 기둥 전체, 테두리는 막대다.
            .background(if (selected) SurfaceVariant else Color.Transparent)
            .clickable(onClickLabel = "이 날 요약 보기", onClick = onSelect)
            // 막대는 그림이라 요일·횟수를 하나의 문장으로 읽어준다. `clearAndSetSemantics`를
            // 쓰면 위의 클릭 동작까지 지워진다.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(if (day.isToday) "오늘 " else "")
                    append(label).append("요일 ").append(day.reps).append("회")
                    if (day.metGoal) append(", 목표 달성")
                }
            }
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 달성 표시 자리는 항상 비워 둔다 — 체크가 있는 날만 높아지면 막대 바닥이 어긋난다.
        Box(Modifier.size(CheckSize), contentAlignment = Alignment.Center) {
            if (day.metGoal) {
                Canvas(Modifier.size(CheckSize)) { drawCheck(LimeGreen) }
            }
        }
        Text(
            text = day.reps.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                day.metGoal -> LimeGreen
                day.reps > 0 -> TextPrimary
                else -> TextDisabled
            },
        )
        Spacer(Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarMaxHeight)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SurfaceVariant)
                .then(
                    // 오늘은 테두리로 표시한다. 라벨의 굵기와 함께 두 가지 신호가 된다.
                    if (day.isToday) {
                        Modifier.border(1.dp, TextMuted, RoundedCornerShape(Radius.sm))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (barHeight > 0.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(Radius.sm))
                        // 목표 미달은 중립색이다. 실패로 읽히는 빨강을 쓰지 않는다.
                        .background(if (day.metGoal) LimeGreen else TextMuted),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (day.isToday) FontWeight.Black else FontWeight.Normal,
            color = if (day.isToday) TextPrimary else TextMuted,
            modifier = Modifier.padding(bottom = Spacing.xs),
        )
    }
}

/** 달성 표시. 브랜드 심볼과 같은 꺾인 체크다. */
internal fun DrawScope.drawCheck(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.18f, h * 0.52f)
        lineTo(w * 0.42f, h * 0.76f)
        lineTo(w * 0.84f, h * 0.24f)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = w * 0.16f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

/** 그래프와 하루치 요약이 같은 이름을 써야 해서 공개한다. 월요일이 0번이다. */
internal val WeekDayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
private val BarMaxHeight = 88.dp
private val MinVisibleBar = 4.dp
private val CheckSize = 12.dp
