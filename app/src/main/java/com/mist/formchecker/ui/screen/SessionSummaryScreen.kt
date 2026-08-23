package com.mist.formchecker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.data.local.ErrorFlags
import com.mist.formchecker.data.local.PerformanceMetricsEntity
import com.mist.formchecker.data.local.RepRecordEntity
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackSuccess
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceCard
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 세션 결과 — rep 목록 + 판정 사실 + 성능 지표 카드 (설계문서 5장).
 *
 * ## 점수를 보여주지 않는다
 * 설계문서 초판은 "폼 점수"를 요구했지만 **경고 5종에 가중치를 매길 근거가 없다.** 대신
 * "판정한 항목 중 몇 개에서 경고가 났는가"라는 사실을 보여준다 (설계문서 4.1절). 숫자
 * 하나로 뭉치면 무엇을 고쳐야 하는지도 함께 사라진다.
 *
 * ## 성능 카드를 기능으로 취급한다
 * 설계문서 173행이 추론지연·프레임드랍을 이 화면에 노출하라고 명시한다 — 8장 측정 지표가
 * 사용자에게 전달되는 유일한 창구다. 디자인을 미루더라도 수치 표시 자체는 뺄 수 없다.
 */
@Composable
fun SessionSummaryScreen(
    sessionId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    showDiagnostics: Boolean = false,
    viewModel: SessionSummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("운동 결과", style = MaterialTheme.typography.headlineSmall)

        // 화면 전체가 하나의 스크롤이다.
        //
        // 이전에는 카드들을 고정 높이로 쌓고 rep 목록만 weight(1f)로 두었는데, **피드백이
        // 길어지면 고정 카드들의 합이 화면 높이를 넘어 아래 버튼이 잘렸다** — 실기기에서
        // "홈으로"가 화면 밖으로 밀려 눌리지 않았다. 해상도마다 잘리는 지점이 달라
        // 카드 높이를 조정하는 방식으로는 못 고친다.
        //
        // 버튼은 이 스크롤 **밖**에 두어 항상 보이게 한다.
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when {
                state.loading -> item {
                    Text(
                        "불러오는 중…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FeedbackInfo,
                    )
                }

                state.session == null -> item {
                    Text(
                        // 조용히 빈 화면을 보여주면 사용자는 기록이 사라졌다고 생각한다.
                        "이 세션을 찾을 수 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FeedbackWarning,
                    )
                }

                else -> {
                    item { OverviewCard(state) }

                    state.report?.let { report ->
                        item { SessionReportCard(report) }
                    }

                    // 성능은 개발 화면에서 넘어왔을 때만 띄운다. 사용자에게는 조치할 수
                    // 없는 값이고, 그만큼 피드백과 동작 기록이 아래로 밀린다.
                    if (showDiagnostics) {
                        state.metrics?.let { metrics ->
                            item { PerformanceCard(metrics) }
                        }
                    }

                    if (state.reps.isNotEmpty()) {
                        item {
                            Text("동작별 기록", style = MaterialTheme.typography.titleSmall)
                        }
                        items(state.reps, key = { it.id }) { RepRow(it) }
                    }
                }
            }
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
        ) {
            Text("홈으로")
        }
    }
}

/**
 * 세션 한눈에.
 *
 * "확인한 횟수"를 함께 보여주는 이유: 판정하지 못한 동작이 있으면 **"짚은 횟수 0"이
 * "자세가 좋았다"로 읽힌다.** 분모를 보여줘야 그 차이가 드러난다 (설계문서 4.2절).
 */
@Composable
private fun OverviewCard(state: SessionSummaryState) {
    SummaryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat("총 횟수", state.reps.size.toString())
            Stat("확인한 횟수", state.judgedReps.toString())
            Stat(
                label = "짚은 횟수",
                value = state.warnedReps.toString(),
                color = if (state.warnedReps == 0) FeedbackSuccess else FeedbackWarning,
            )
        }
        val unjudged = state.reps.size - state.judgedReps
        if (unjudged > 0) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "${unjudged}번은 자세를 확인하지 못했어요. " +
                    "카메라에 몸이 다 들어오지 않았거나 방향이 맞지 않았을 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = FeedbackInfo,
            )
        }
    }
}

@Composable
private fun PerformanceCard(metrics: PerformanceMetricsEntity) {
    SummaryCard {
        Text("성능", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat("추론 평균", metrics.avgInferenceMs?.let { "${it.toInt()}ms" } ?: "—")
            Stat("추론 p95", metrics.p95InferenceMs?.let { "${it.toInt()}ms" } ?: "—")
            Stat("fps", metrics.avgFps?.let { String.format("%.1f", it) } ?: "—")
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat("모델 로드", metrics.modelLoadMs?.let { "${it}ms" } ?: "—")
            Stat(
                "프레임 드랍",
                metrics.droppedFrameRatio?.let { String.format("%.1f%%", it * 100) } ?: "—",
            )
            Stat("delegate", metrics.delegateType?.uppercase() ?: "—")
        }
    }
}

/**
 * 동작 한 줄. 판정 사실만 적는다.
 *
 * `checked_count`가 0이면 **"확인 못 함"으로 명시한다** — "피드백 없음"과 재지 못한 것이
 * 같아 보이면 안 된다 (설계문서 4.2절).
 */
@Composable
private fun RepRow(rep: RepRecordEntity) {
    val warned = remember(rep.errorFlags) { ErrorFlags.warned(rep.errorFlags) }
    val tone = when {
        rep.checkedCount == 0 -> FeedbackInfo
        warned.isEmpty() -> FeedbackSuccess
        else -> FeedbackWarning
    }

    SummaryCard(padding = Spacing.sm) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                rep.repNumber.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tone,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(String.format("%.1f초", rep.durationMs / 1000f))
                        append(" · ")
                        // SIDE·SHALLOW 같은 enum 이름을 그대로 보여주고 있었다.
                        append(FeedbackLabels.angle(rep.cameraAngle))
                        rep.depthLevel?.let { append(" · ${FeedbackLabels.depth(it)}") }
                        rep.depthScore?.let { append(String.format(" (%.0f%%)", it * 100)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        rep.checkedCount == 0 -> "확인 못 함"
                        warned.isEmpty() -> "피드백 없음"
                        else -> warned.joinToString(", ") { FeedbackLabels.shortName(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tone,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    padding: Dp = Spacing.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceCard)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun Stat(label: String, value: String, color: Color? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = FeedbackInfo)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
