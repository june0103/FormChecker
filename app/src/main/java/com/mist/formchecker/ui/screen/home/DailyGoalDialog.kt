package com.mist.formchecker.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mist.formchecker.data.DailyGoal
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 하루 목표를 정하는 창.
 *
 * ## 잘못된 입력을 막는 두 겹
 * 1. 입력칸이 **숫자만** 받고 세 자리에서 끊는다(범위 상한이 세 자리다). 붙여넣기로 들어온
 *    문자·부호도 여기서 걸린다.
 * 2. 그래도 범위를 벗어나면(0, 501) 저장 버튼이 꺼지고 왜 안 되는지 적는다.
 *
 * ## 아직 목표가 없으면 빈 칸으로 연다
 * 임의의 숫자를 미리 넣어두면 사용자가 정하지 않은 값이 "내 목표"가 된다. 증감 버튼은
 * 빈 칸을 0으로 보고 한 칸 올린다 — 처음 눌렀을 때 [DailyGoal.STEP]에서 시작한다.
 */
@Composable
internal fun DailyGoalDialog(
    initial: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial?.toString() ?: "") }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && DailyGoal.valid(parsed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "오늘의 목표 정하기" else "오늘의 목표 바꾸기") },
        text = {
            Column {
                Text(
                    "하루 동안 할 운동 총횟수예요. 세트 수가 아니라 하루 합계입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(Spacing.md))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepButton(
                        label = "−",
                        description = "${DailyGoal.STEP}회 줄이기",
                        onClick = { text = DailyGoal.step(parsed ?: 0, -DailyGoal.STEP).toString() },
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { input ->
                            text = input.filter { it.isDigit() }.take(MaxDigits)
                        },
                        singleLine = true,
                        isError = text.isNotEmpty() && !valid,
                        placeholder = { Text("예: 30") },
                        suffix = { Text("회") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    StepButton(
                        label = "+",
                        description = "${DailyGoal.STEP}회 늘리기",
                        onClick = { text = DailyGoal.step(parsed ?: 0, DailyGoal.STEP).toString() },
                    )
                }
                if (text.isNotEmpty() && !valid) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "${DailyGoal.RANGE.first}회에서 ${DailyGoal.RANGE.last}회 사이로 정해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FeedbackWarning,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        // 접근성 최소 터치 타겟 (디자인시스템 68행).
        modifier = Modifier
            .width(TouchTarget.minSize)
            .heightIn(min = TouchTarget.minSize)
            // 화면에 보이는 것은 기호 하나뿐이라 스크린 리더에는 뜻을 읽어준다.
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private const val MaxDigits = 3
