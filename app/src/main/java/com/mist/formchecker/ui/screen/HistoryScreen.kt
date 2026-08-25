package com.mist.formchecker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.data.local.SessionListItem
import com.mist.formchecker.ui.component.ScreenHeader
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.FeedbackDanger
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackSuccess
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceCard
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기록 — 세션 리스트 + 동기화 대기(pending) 배지 (설계문서 5장).
 *
 * pending 배지는 장식이 아니라 오프라인 우선 원칙(설계문서 195행)이 사용자에게 드러나는
 * 지점이다. **Supabase 동기화가 아직 없으므로 지금은 항상 전체 건수가 뜬다** — 0으로
 * 위장하면 "저장됐고 전송됐다"로 읽힌다. 동기화가 붙으면 이 숫자가 저절로 줄어든다.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSession: (String) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().background(BgBase)) {
        // 업로드 대기 배지도 이 줄에 얹는다 — 제목 옆이 원래 자리였고, 따로 한 줄을
        // 쓰면 목록이 그만큼 밀린다.
        ScreenHeader(
            onBack = onBack,
            title = "기록",
            trailing = { if (state.pendingCount > 0) PendingBadge(state.pendingCount) },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
        if (state.isEmpty) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "아직 기록이 없어요.\n운동을 마치면 여기에 하나씩 쌓여요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FeedbackInfo,
                )
            }
        } else {
            // ## 한 목록에 두 종류가 섞인다
            //
            // 날짜 헤더와 세션 카드다. `contentType`을 주면 Compose가 같은 종류끼리만
            // 재사용한다 — 주지 않으면 헤더 자리에 카드 레이아웃을 재활용하려다 매번
            // 새로 만든다(RecyclerView의 뷰 타입과 같은 역할).
            //
            // 헤더를 고정(sticky)하지 않는다. 실험 API가 필요하고, 지금 기록 수(수십 개)에서는
            // 목록과 함께 흐르는 편이 단순하다.
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.days.forEach { day ->
                    item(key = "day-${day.startMs}", contentType = "header") {
                        DayHeader(day)
                    }
                    items(
                        items = day.sessions,
                        key = { it.session.id },
                        contentType = { "session" },
                    ) { item ->
                        SessionRow(
                            item = item,
                            onOpen = { onOpenSession(item.session.id) },
                            onDelete = { viewModel.delete(item.session.id) },
                        )
                    }
                }
            }
        }

        }
    }
}

/** 아직 서버로 보내지 않은 행 수. 동기화가 붙기 전에는 전체 건수다. */
@Composable
private fun PendingBadge(count: Int) {
    Text(
        // "동기화"는 개발자 말이다. 사용자에게는 "아직 안 올라간 것"이 전부다.
        "업로드 대기 ${count}건",
        style = MaterialTheme.typography.labelMedium,
        color = FeedbackWarning,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(FeedbackWarningContainer)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

/**
 * 날짜 한 줄. **카드가 아니다** — 배경 없이 글자만 두어 아래 카드들이 그 날에 속한
 * 것으로 읽히게 한다.
 *
 * 그날 합계를 함께 말한다. 카드를 더해 보지 않아도 그날 얼마나 했는지 보여야 한다.
 */
@Composable
private fun DayHeader(day: HistoryDay) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 위쪽만 띄운다. 날짜와 그 아래 첫 카드는 붙어 있어야 한 묶음으로 보인다 —
            // 앞 묶음과의 간격(md + 목록 간격)이 헤더와 자기 카드 사이(목록 간격)보다
            // 넓어야 어디서 날이 바뀌는지 눈으로 잡힌다.
            .padding(top = Spacing.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = formatDay(day),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = "  ·  총 ${day.totalReps}회",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun SessionRow(item: SessionListItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    // 삭제는 두 번 눌러야 한다 — 기록은 지우면 되돌릴 수 없고, 아직 서버 사본도 없다.
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceCard)
            .clickable(onClick = onOpen)
            .padding(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // **날짜를 쓰지 않는다** — 바로 위 헤더가 그 날을 말한다. 카드마다
                    // 같은 날짜를 반복하면 정작 다른 값(시각·횟수)이 묻힌다.
                    formatTime(item.session.startedAt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    buildString {
                        append("${item.repCount}번")
                        if (item.repCount > 0) {
                            append(" · 피드백 ${item.warnedRepCount}번")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.warnedRepCount == 0 && item.repCount > 0) {
                        FeedbackSuccess
                    } else {
                        FeedbackInfo
                    },
                )
            }
            if (item.pendingCount > 0) {
                Text(
                    "대기 ${item.pendingCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FeedbackWarning,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
        TextButton(onClick = { if (confirming) onDelete() else confirming = true }) {
            Text(
                if (confirming) "정말 지울까요? 한 번 더 누르시면 삭제돼요" else "삭제",
                style = MaterialTheme.typography.labelSmall,
                color = FeedbackDanger,
            )
        }
    }
}

/**
 * 날짜 헤더에 쓸 말. 괄호 안이 요일이거나 `오늘`·`어제`다.
 *
 * 날짜를 항상 함께 적는다 — `오늘`만 적으면 며칠인지 알 수 없고, 화면을 켜 둔 채 날이
 * 바뀌면 무엇을 보고 있는지도 흐려진다.
 */
private fun formatDay(day: HistoryDay): String {
    val date = SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(day.startMs))
    val suffix = when (day.relative) {
        RelativeDay.TODAY -> "오늘"
        RelativeDay.YESTERDAY -> "어제"
        RelativeDay.OTHER -> SimpleDateFormat("E", Locale.KOREA).format(Date(day.startMs))
    }
    return "$date ($suffix)"
}

/**
 * 카드에 보여줄 시각. **날짜는 없다** — 헤더가 말한다.
 *
 * `SimpleDateFormat`을 쓰는 이유: `minSdk 24`라 `java.time`은 core library desugaring이
 * 필요하고, 화면 한 줄 때문에 빌드 설정을 건드릴 이유가 없다.
 */
private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(epochMs))
