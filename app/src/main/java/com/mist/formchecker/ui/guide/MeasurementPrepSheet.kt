package com.mist.formchecker.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget

/** 이 안내를 어떤 맥락에서 열었나. 버튼과 뒷일이 달라진다. */
enum class PrepMode {
    /** 첫 운동을 시작하려던 참. 확인하면 저장하고 운동 화면으로 간다. */
    BEFORE_FIRST_WORKOUT,

    /** 홈의 `측정 팁`이나 설정의 도움말에서 다시 열었다. **운동을 자동으로 시작하지 않는다.** */
    REVIEW,
}

/**
 * 카메라 앞에 서기 전에 확인할 것들.
 *
 * ## 왜 운동 화면이 아니라 그 앞인가
 * 이 안내는 **폰을 세우기 전에** 읽어야 쓸모가 있다. 카메라 화면에 들어간 뒤에는 이미
 * 자리를 잡은 뒤이고, 그 거리에서는 글이 읽히지도 않는다.
 *
 * ## 확인해야 넘어간다 — 다만 강요하지 않는다
 * `준비됐어요`를 눌렀을 때만 완료로 저장하고 운동으로 넘어간다. 닫거나 `다음에 운동할게요`를
 * 고르면 홈에 그대로 남고 **완료로 치지 않는다** — 읽지 않은 안내를 읽은 것으로 만들지 않는다.
 *
 * ## 복장 이야기를 경고로 만들지 않는다
 * 자세 오류가 아니라 촬영 환경 문제다. Danger 색을 쓰지 않고 [FeedbackInfo] 계열로 두며,
 * "측정 정확도를 낮출 수 있어요"까지만 말한다. 특정 복장을 비난하거나 노출을 요구하지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementPrepSheet(
    mode: PrepMode,
    onDismiss: () -> Unit,
    onReady: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 두 번 눌러 운동 화면이 두 번 열리는 것을 막는다. 시트가 닫히는 동안에도 버튼은
    // 잠깐 살아 있다.
    var starting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgBase,
    ) {
        Column(
            modifier = Modifier
                // 작은 화면·큰 글자에서 버튼이 잘리지 않게 스크롤한다.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.lg),
        ) {
            Text(
                text = "측정 전, 이것만 확인해 주세요",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(Spacing.md))

            PrepChecklist.forEach { line ->
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                    // 색만으로 목록임을 표시하지 않는다.
                    Text("·  ", style = MaterialTheme.typography.bodyMedium, color = LimeGreen)
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = ClothingNote,
                style = MaterialTheme.typography.bodySmall,
                color = FeedbackInfo,
            )

            Spacer(Modifier.height(Spacing.lg))
            when (mode) {
                PrepMode.BEFORE_FIRST_WORKOUT -> {
                    Button(
                        onClick = {
                            if (!starting) {
                                starting = true
                                onReady()
                            }
                        },
                        enabled = !starting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = ActionHeight),
                    ) {
                        Text("준비됐어요", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
                    ) {
                        Text("다음에 운동할게요", style = MaterialTheme.typography.labelLarge)
                    }
                }

                PrepMode.REVIEW -> {
                    // 다시 보기에서는 운동으로 가는 길을 주지 않는다 — 확인만 하러 온
                    // 사람을 카메라 화면으로 밀어내지 않는다.
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
                    ) {
                        Text("확인", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** 홈의 `운동 시작` 아래에 늘 떠 있는 한 줄. 시트를 열지 않아도 핵심은 보이게 한다. */
const val PrepShortHint = "전신과 무릎·발목이 잘 보이도록 준비해 주세요."

private val PrepChecklist = listOf(
    "머리부터 발끝까지 화면 안에 보이나요?",
    "휴대폰이 움직이지 않도록 고정했나요?",
    "주변이 밝고 한 사람만 화면에 보이나요?",
    "무릎과 발목의 움직임이 잘 보이는 복장인가요?",
    "옷이나 신발이 발과 발목을 가리지 않나요?",
    "안전하게 움직일 수 있는 공간을 확보했나요?",
)

private const val ClothingNote =
    "통이 넓은 바지나 관절을 가리는 옷, 발 모양이 잘 보이지 않는 신발은 " +
        "측정 정확도를 낮출 수 있어요."

private val ActionHeight = 56.dp
