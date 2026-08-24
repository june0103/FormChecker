package com.mist.formchecker.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.ui.screen.workout.CalibrationPrep
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 설정.
 *
 * ## 지금 있는 것
 * 준비 시간뿐이다. 운동 화면에 있던 선택 줄을 옮겨 왔다 — 매번 같은 자리에서 찍는
 * 사람에게는 그 줄이 화면만 차지했고, 프리뷰는 이미 폭에서 막혀 있어 줄을 없애도 카메라가
 * 커지지는 않지만 화면이 단순해진다.
 *
 * ## 아직 없는 것
 * 설계문서 5장이 요구한 delegate(CPU/GPU)·해상도 선택은 **측정용 실험 노브**이고, 지금은
 * 개발용 운동 화면에 모델 전환만 있다. 세트 사이 휴식 시간은 세트 개념이 생길 때 여기
 * 붙는다(개편 목록 ③).
 *
 * ## 값을 되돌리는 버튼을 두지 않는다
 * 설정이 하나뿐이고 기본값이 목록 안에 표시돼 있다. 항목이 늘면 그때 필요해진다.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prepSeconds by viewModel.prepSeconds.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineLarge)

        Text(
            text = "준비 시간",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            // 왜 고를 수 있는지를 적는다. 이 값을 화면에서 설정으로 옮긴 이유가 바로
            // "사람마다 다르지만 한 사람에게는 거의 안 바뀐다"였다.
            text = "기준 자세 재기를 누른 뒤 측정이 시작되기까지 기다리는 시간이에요. " +
                "버튼을 누른 자리에서 카메라 앞까지 걸어갈 시간입니다 — 삼각대를 앞에 두고 " +
                "화면에 손이 닿으면 '바로'가 맞고, 방 건너편에 두었으면 10초도 짧아요.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CalibrationPrep.OPTIONS.forEach { seconds ->
                val selected = seconds == prepSeconds
                val label = if (seconds == 0) "바로" else "${seconds}초"
                val modifierFor = Modifier.weight(1f).height(TouchTarget.minSize)
                if (selected) {
                    Button(
                        onClick = { viewModel.selectPrepSeconds(seconds) },
                        modifier = modifierFor,
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.selectPrepSeconds(seconds) },
                        modifier = modifierFor,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        if (prepSeconds == CalibrationPrep.DEFAULT_SECONDS) {
            Text(
                text = "기본값이에요.",
                style = MaterialTheme.typography.labelSmall,
                color = LimeGreen,
            )
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
        ) { Text("뒤로", style = MaterialTheme.typography.labelLarge) }
    }
}
