package com.mist.formchecker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.Finding
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.NotJudged
import com.mist.formchecker.poseengine.SessionReport
import com.mist.formchecker.poseengine.Side
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackSuccess
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing

/**
 * 세션 피드백 카드.
 *
 * ## 점수를 보여주지 않는다
 * "무엇이 몇 번 나왔는가"와 "그래서 무엇을 하라"만 적는다. 숫자 하나로 뭉치면 무엇을
 * 고쳐야 하는지가 사라지고, 그 숫자의 가중치에 근거도 없다 (설계문서 4.1절).
 *
 * ## 문제가 없을 때 "잘했다"고 하지 않는다
 * 확정된 임계값 전부가 "정상을 오탐하지 않는가"만 검증됐고 **"오류를 잡는가"는 미검증**이다
 * (의도적 오류 세션이 아직 없다). 그래서 "경고가 난 rep이 없습니다"라는 사실로만 쓴다.
 */
@Composable
fun SessionReportCard(report: SessionReport, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(FeedbackWarningContainer.takeIf { report.findings.isNotEmpty() } ?: SurfaceTone)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("자세 피드백", style = MaterialTheme.typography.titleSmall)

        when {
            report.judgedReps == 0 -> Text(
                "자세를 판정할 수 있는 rep이 없었습니다. " +
                    "카메라에 몸 전체가 들어오는지, 각도가 맞는지 확인해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackInfo,
            )

            report.findings.isEmpty() -> Text(
                // "자세가 좋았습니다"라고 쓰지 않는다 — 임계값이 오류를 잡는지는 미검증이다.
                "경고가 난 rep이 없습니다. (판정한 rep ${report.judgedReps}개)",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackSuccess,
            )

            else -> report.findings.forEachIndexed { index, finding ->
                FindingRow(finding, primary = index == 0)
            }
        }

        report.depth?.let { DepthLine(it.averageScore, it.levels, it.judgedReps) }

        if (report.notJudged.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                // 못 본 것을 말하지 않으면 "문제 없음"이 "전부 괜찮음"으로 읽힌다.
                report.notJudged.notJudgedSentence(),
                style = MaterialTheme.typography.bodySmall,
                color = FeedbackInfo,
            )
        }
    }
}

@Composable
private fun FindingRow(finding: Finding, primary: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (primary) "1" else "·",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = FeedbackWarning,
        )
        Column(Modifier.weight(1f)) {
            Text(
                finding.advice(),
                style = if (primary) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
                color = FeedbackWarning,
            )
            Text(
                finding.frequency(),
                style = MaterialTheme.typography.bodySmall,
                color = FeedbackInfo,
            )
        }
    }
}

@Composable
private fun DepthLine(averageScore: Float?, levels: Map<DepthLevel, Int>, judgedReps: Int) {
    Text(
        buildString {
            append("깊이: ")
            if (averageScore != null) {
                append(String.format("패럴렐 도달률 평균 %.0f%%", averageScore * 100))
                append(" · ")
            }
            append(
                DepthLevel.entries
                    .filter { levels[it] != null }
                    .joinToString(" / ") { "${it.label()} ${levels[it]}" },
            )
            append(" (rep ${judgedReps}개)")
        },
        style = MaterialTheme.typography.bodySmall,
        color = FeedbackInfo,
    )
}

/**
 * 무엇을 하라. **언제인지를 함께 적는다** — "무릎을 벌리세요"만으로는 서 있을 때인지
 * 앉을 때인지 알 수 없고, 실제로 문제가 생기는 시점은 최저점 근처다.
 *
 * 쪽이 있으면 붙인다. 실측에서 어느 쪽이 나쁜지는 사람 안에서 90~100% 일관됐다.
 */
private fun Finding.advice(): String {
    val side = when (this.side) {
        Side.LEFT -> "왼쪽 "
        Side.RIGHT -> "오른쪽 "
        null -> ""
    }
    return when (warning) {
        FormWarning.KNEE_VALGUS ->
            "앉을 때 ${side}무릎을 더 벌리세요. 무릎이 발끝 방향을 향해야 합니다."
        FormWarning.KNEE_FLARED ->
            "앉을 때 ${side}무릎을 발끝 방향으로 모으세요. 지금은 발끝보다 바깥으로 벌어집니다."
        FormWarning.SHALLOW_DEPTH ->
            "더 깊게 앉으세요. 엉덩이가 무릎 높이까지 내려가야 합니다."
        FormWarning.EXCESSIVE_LEAN ->
            "앉을 때 상체를 더 세우세요. 상체가 45°보다 많이 기울었습니다."
        FormWarning.HIP_SHIFT ->
            "체중이 한쪽으로 쏠렸습니다. 앉을 때 양발에 고르게 실으세요."
    }
}

/** 횟수를 그대로 적는다. "몇 % 이상이면 습관"이라는 경계에 근거가 없다. */
private fun Finding.frequency(): String = when {
    everyRep -> "판정한 rep ${judgedReps}개 모두에서 나왔습니다"
    else -> "rep ${judgedReps}개 중 ${repCount}회"
}

/** 못 본 항목을 한 문장으로. 이유가 같은 것끼리 묶는다. */
private fun List<NotJudged>.notJudgedSentence(): String {
    val byAngle = filter { it.reason == NotJudged.Reason.CAMERA_ANGLE }
    val noRep = filter { it.reason == NotJudged.Reason.NO_JUDGED_REP }

    return buildString {
        if (byAngle.isNotEmpty()) {
            append("이 각도에서는 ")
            append(byAngle.map { it.warning.topic() }.distinct().joinToString(", "))
            append("을 보지 못했습니다. 다른 각도로도 찍어보세요.")
        }
        if (noRep.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(noRep.map { it.warning.topic() }.distinct().joinToString(", "))
            append("은 키포인트가 부족해 판정되지 않았습니다.")
        }
    }
}

/** 항목 이름. 무릎 두 방향은 같은 항목이라 하나로 묶는다. */
private fun FormWarning.topic(): String = when (this) {
    FormWarning.SHALLOW_DEPTH -> "깊이"
    FormWarning.EXCESSIVE_LEAN -> "상체 숙임"
    FormWarning.HIP_SHIFT -> "골반 쏠림"
    FormWarning.KNEE_VALGUS, FormWarning.KNEE_FLARED -> "무릎 정렬"
}

private fun DepthLevel.label(): String = when (this) {
    DepthLevel.STANDING -> "서 있음"
    DepthLevel.SHALLOW -> "부족"
    DepthLevel.PARALLEL -> "적정"
    DepthLevel.DEEP -> "충분"
}

private val SurfaceTone = com.mist.formchecker.ui.theme.SurfaceCard
