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
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.FeedbackDanger
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackSuccess
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceCard
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("기록", style = MaterialTheme.typography.headlineSmall)
            if (state.pendingCount > 0) PendingBadge(state.pendingCount)
        }

        if (state.sessions.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "아직 기록이 없습니다.\n운동을 마치면 여기에 쌓입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FeedbackInfo,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.sessions, key = { it.session.id }) { item ->
                    SessionRow(
                        item = item,
                        onOpen = { onOpenSession(item.session.id) },
                        onDelete = { viewModel.delete(item.session.id) },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
        ) {
            Text("뒤로")
        }
    }
}

/** 아직 서버로 보내지 않은 행 수. 동기화가 붙기 전에는 전체 건수다. */
@Composable
private fun PendingBadge(count: Int) {
    Text(
        "동기화 대기 ${count}건",
        style = MaterialTheme.typography.labelMedium,
        color = FeedbackWarning,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(FeedbackWarningContainer)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
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
                    formatTime(item.session.startedAt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    buildString {
                        append("rep ${item.repCount}개")
                        if (item.repCount > 0) {
                            append(" · 경고 ${item.warnedRepCount}개")
                        }
                        item.session.poseModel?.let { append(" · $it") }
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
                if (confirming) "정말 삭제할까요? 한 번 더 누르세요" else "삭제",
                style = MaterialTheme.typography.labelSmall,
                color = FeedbackDanger,
            )
        }
    }
}

/**
 * 목록에 보여줄 시각.
 *
 * `SimpleDateFormat`을 쓰는 이유: `minSdk 24`라 `java.time`은 core library desugaring이
 * 필요하고, 화면 한 줄 때문에 빌드 설정을 건드릴 이유가 없다.
 */
private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA).format(Date(epochMs))
