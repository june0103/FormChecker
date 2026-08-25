package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mist.formchecker.R
import com.mist.formchecker.ui.component.GifImage
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 스쿼트 동작 예시 — 측정 준비 화면에서 열리는 시트.
 *
 * ## 왜 별도 화면이 아니라 시트인가
 * 운동 화면을 떠나지 않고 잠깐 확인하는 것이다. 화면을 갈아타면 카메라를 껐다 켜야 하고,
 * 돌아왔을 때 기준 자세를 다시 재야 한다.
 *
 * ## 정면·측면을 나누지 않는다
 * 예시 영상이 사선에서 찍혀 있어 한 장면으로 앉는 깊이와 무릎 방향이 함께 보인다.
 * 방향별로 탭을 만들면 **없는 자료를 있는 것처럼** 나누는 것이 되고, 어느 쪽으로 찍을지는
 * 앱이 기준 자세 창에서 스스로 판별하므로 사용자가 고를 일도 아니다.
 *
 * ## 영상 위에 아무것도 얹지 않는다
 * 관절선·수치·개발용 표시를 그리지 않는다. 여기서 보여줄 것은 **사람이 따라 할 동작**이고,
 * 판정 근거는 운동 화면이 실시간으로 말한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SquatExampleSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgBase,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "스쿼트 동작 예시",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(Spacing.md))

            GifImage(
                resId = R.raw.squat_form_example,
                // 그림이 이 시트의 내용이다. 아래 글과 겹치지 않게 동작만 말한다.
                contentDescription = "스쿼트 동작 예시 영상",
                modifier = Modifier
                    .fillMaxWidth()
                    // 폭에 맞춘 높이가 화면을 넘지 않도록 상한을 둔다. 비율은 그대로고
                    // 잘리지 않는다(FIT_CENTER).
                    .heightIn(max = MaxGifHeight),
            )

            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "발을 어깨너비 정도로 벌리고 뒤꿈치를 바닥에 고정하세요. " +
                    "엉덩이를 뒤로 보내며 무릎을 굽히고, 허벅지가 바닥과 평행해지는 " +
                    "정도까지만 내려갔다가 천천히 일어나세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )

            Spacer(Modifier.height(Spacing.lg))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
            ) {
                Text("닫기", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 시트 안에서 그림이 차지할 수 있는 최대 높이. 글과 닫기 버튼이 함께 보여야 한다. */
private val MaxGifHeight = 400.dp
