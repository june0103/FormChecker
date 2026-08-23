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
import com.mist.formchecker.poseengine.DepthSummary
import com.mist.formchecker.poseengine.Finding
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.NotJudged
import com.mist.formchecker.poseengine.RepPhase
import com.mist.formchecker.poseengine.SessionReport
import com.mist.formchecker.poseengine.Side
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackSuccess
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceCard

/**
 * 자세 피드백 카드.
 *
 * ## 트레이너가 말하듯 쓴다
 * 사용자는 자세를 처음 배우는 사람이다. `rep`·`패럴렐`·`valgus` 같은 용어를 쓰지 않고,
 * **몸으로 확인할 수 있는 말**로 바꾼다 — "패럴렐까지 앉으세요"가 아니라 "엉덩이가 무릎
 * 높이까지 내려오면 딱 좋아요". 용어 매핑은 [FeedbackLabels]에 모아 둔다.
 *
 * 임계값 숫자도 문구에 넣지 않는다. "45°보다 기울었습니다"는 사용자가 확인할 수 없다 —
 * 무엇을 어떻게 바꾸라는 말만 쓰고 숫자는 KDoc에 남긴다.
 *
 * ## 점수를 보여주지 않는다
 * "무엇이 몇 번 나왔는가"와 "그래서 무엇을 하라"만 적는다. 숫자 하나로 뭉치면 무엇을
 * 고쳐야 하는지가 사라지고, 그 숫자의 가중치에 근거도 없다 (설계문서 4.1절).
 *
 * ## 문제가 없을 때 "잘했다"고 하지 않는다
 * 확정된 임계값 전부가 "정상을 오탐하지 않는가"만 검증됐고 **"오류를 잡는가"는 미검증**이다
 * (의도적 오류 세션이 아직 없다). 말투는 부드럽게 하되 **"짚을 부분이 없었다"는 사실**까지만
 * 말한다 — "자세가 좋아요"는 데이터가 뒷받침하지 못한다.
 */
@Composable
fun SessionReportCard(report: SessionReport, modifier: Modifier = Modifier) {
    val hasFindings = report.findings.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(if (hasFindings) FeedbackWarningContainer else SurfaceCard)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("오늘 자세 피드백", style = MaterialTheme.typography.titleSmall)

        when {
            report.judgedReps == 0 -> Text(
                "자세를 확인할 수 있는 동작이 없었어요. 카메라에 몸 전체가 들어왔는지, " +
                    "방향이 맞는지 확인하고 다시 해볼까요?",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackInfo,
            )

            !hasFindings -> Text(
                // "자세가 좋아요"라고 쓰지 않는다 — 임계값이 오류를 잡는지는 미검증이다.
                "${report.judgedReps}번 모두 확인했는데 따로 짚을 부분은 없었어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackSuccess,
            )

            else -> report.findings.forEachIndexed { index, finding ->
                FindingRow(finding, primary = index == 0)
            }
        }

        report.depth?.let { DepthLine(it) }

        if (report.notJudged.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                // 못 본 것을 말하지 않으면 "짚을 게 없다"가 "전부 괜찮다"로 읽힌다.
                report.notJudged.sentence(),
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
private fun DepthLine(depth: DepthSummary) {
    Text(
        buildString {
            append("앉은 깊이는 ")
            depth.averageScore?.let {
                append(String.format("목표의 평균 %.0f%%까지 내려갔어요. ", it * 100))
            }
            append(
                DepthLevel.entries
                    .mapNotNull { level ->
                        depth.levels[level]?.let { "${FeedbackLabels.depth(level)} ${it}번" }
                    }
                    .joinToString(", ", prefix = "(", postfix = ")"),
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = FeedbackInfo,
    )
}

/**
 * 무엇을 어떻게 하라.
 *
 * **언제인지를 함께 말한다** — "무릎을 벌리세요"만으로는 서 있을 때인지 앉을 때인지 알 수
 * 없고, 실제로 문제가 생기는 시점은 가장 낮게 앉았을 때다.
 *
 * 쪽이 있으면 붙인다. 실측에서 어느 쪽이 나쁜지는 사람 안에서 90~100% 일관됐다.
 */
private fun Finding.advice(): String {
    val where = when (side) {
        Side.LEFT -> "왼쪽 "
        Side.RIGHT -> "오른쪽 "
        null -> ""
    }
    // 구간을 아는 경우에만 붙인다. 모르면(예전 기록) 침묵한다 — 없는 정보를 지어내는
    // 것보다 낫다.
    val when_ = phase.label()

    return when (warning) {
        FormWarning.KNEE_VALGUS ->
            "${when_}${where}무릎이 안쪽으로 모여요. " +
                "무릎을 발끝 방향으로 밀어내면서 움직여 보세요."

        FormWarning.KNEE_FLARED ->
            "${when_}${where}무릎이 발끝보다 바깥으로 벌어져요. " +
                "무릎을 발끝 방향에 맞춰 주세요."

        FormWarning.SHALLOW_DEPTH ->
            "조금 더 깊게 앉아 주세요. 엉덩이가 무릎 높이까지 내려오면 딱 좋아요."

        FormWarning.EXCESSIVE_LEAN ->
            "${when_.ifEmpty { "내려갈 때 " }}상체가 많이 앞으로 숙여져요. " +
                "가슴을 들고 시선은 앞을 보면서 움직여 보세요."

        FormWarning.HIP_SHIFT ->
            "${when_}체중이 한쪽 발로 쏠려요. 양발에 고르게 실어 주세요."
    }
}

/**
 * 언제 그랬는지. 문장 앞에 붙는다.
 *
 * ## 왜 구간을 말하는가
 * "무릎이 모여요"와 "무릎이 벌어져요"가 한 세션에 함께 나올 수 있다 — 내려갈 때와 올라올
 * 때가 다른 사람이 있다. **언제인지를 말하지 않으면 둘 중 무엇을 언제 고쳐야 하는지 알 수
 * 없다.** 둘 다 실제로 일어난 일이고, 구간이 다르면 고치는 방법도 다르다.
 *
 * null이면 구간을 기록하지 않은 기록이다(이 기능 이전). 그때는 아무것도 붙이지 않는다.
 */
private fun RepPhase?.label(): String = when (this) {
    RepPhase.DESCENDING -> "내려갈 때 "
    RepPhase.BOTTOM -> "가장 낮은 지점에서 "
    RepPhase.ASCENDING -> "올라올 때 "
    RepPhase.THROUGHOUT -> "동작 내내 "
    null -> ""
}

/**
 * 몇 번 나왔나.
 *
 * "자주", "가끔" 같은 말을 쓰지 않는다 — 그 경계에 근거가 없다. 횟수를 그대로 적고,
 * 전부에서 났을 때만 "모두"라고 쓴다(그건 임계값이 아니라 사실이다).
 */
private fun Finding.frequency(): String =
    if (everyRep) "확인한 ${judgedReps}번 모두에서 나왔어요" else "${judgedReps}번 중 ${repCount}번"

/**
 * 못 본 것을 한 문장으로.
 *
 * 이유를 구분해서 말한다 — 각도 때문이면 **다른 방향에서 찍으면 풀리고**, 인식 문제면
 * 각도를 바꿔도 안 풀린다. 사용자가 다음에 무엇을 바꿀지 알아야 한다.
 */
private fun List<NotJudged>.sentence(): String {
    val byAngle = filter { it.reason == NotJudged.Reason.CAMERA_ANGLE }
        .map { FeedbackLabels.topic(it.warning) }
        .distinct()
    val notCaught = filter { it.reason == NotJudged.Reason.NO_JUDGED_REP }
        .map { FeedbackLabels.topic(it.warning) }
        .distinct()

    return buildString {
        if (byAngle.isNotEmpty()) {
            append("이번엔 ${byAngle.joinToString(", ")}은 볼 수 없었어요. ")
            append("카메라 방향 때문이라, 다음엔 다른 방향에서도 한 세트 찍어보시면 같이 봐드릴게요.")
        }
        if (notCaught.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append("${notCaught.joinToString(", ")}은 몸이 잘 안 잡혀서 확인하지 못했어요.")
        }
    }
}
