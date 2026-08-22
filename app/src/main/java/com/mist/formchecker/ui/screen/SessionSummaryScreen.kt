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

        when {
            state.loading -> Text(
                "불러오는 중…",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackInfo,
            )

            state.session == null -> Text(
                // 조용히 빈 화면을 보여주면 사용자는 기록이 사라졌다고 생각한다.
                "이 세션을 찾을 수 없습니다.\n세션 ID: $sessionId",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackWarning,
            )

            else -> {
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
                            // 판정 불가와 "괜찮았다"를 구분해 준다. 이 문장이 없으면
                            // 경고 0건이 자세가 좋았다는 뜻으로 읽힌다.
                            "${unjudged}번은 자세를 확인하지 못했어요. " +
                                "카메라에 몸이 다 들어오지 않았거나 방향이 맞지 않았을 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FeedbackInfo,
                        )
                    }
                }

                // 리포트를 성능 카드보다 먼저 둔다 — 사용자가 이 화면에서 찾는 것은
                // 추론 지연이 아니라 "무엇을 고쳐야 하나"다.
                state.report?.let { SessionReportCard(it) }

                state.metrics?.let { PerformanceCard(it) }

                Text(
                    "동작별 기록",
                    style = MaterialTheme.typography.titleSmall,
                )
                LazyColumn(
                    // 카드가 늘어나도 rep 목록이 최소 높이를 갖도록 weight를 준다.
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(state.reps, key = { it.id }) { RepRow(it) }
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
 * `checked_count`가 0이면 **"확인 못 함"으로 명시한다** — "짚을 것 없음"과 재지 못한 것이
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
                        warned.isEmpty() -> "짚을 것 없음"
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
