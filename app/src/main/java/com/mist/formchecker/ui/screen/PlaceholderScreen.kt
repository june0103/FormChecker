package com.mist.formchecker.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TouchTarget

// ─────────────────────────────────────────────────────────────
// Phase 0 뼈대 전용 임시 화면. 화면 간 이동이 되는지 확인하는 용도이며,
// 각 화면의 실제 내용이 구현되면 순차적으로 제거된다.
//
// 색상은 전부 MaterialTheme.colorScheme을 통해서만 참조한다 (디자인시스템 90번
// 적용 원칙 1). 디자인 작업을 Phase 6으로 미룬 결정이 나중에 대공사가 되지
// 않으려면 이 규칙이 뼈대 단계에서부터 지켜져야 한다.
// ─────────────────────────────────────────────────────────────

/** 임시 화면에서 아래쪽에 나열할 이동 버튼 하나. */
data class PlaceholderAction(
    val label: String,
    val emphasized: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    actions: List<PlaceholderAction>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        actions.forEach { action ->
            val buttonModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget.minSize)

            if (action.emphasized) {
                Button(onClick = action.onClick, modifier = buttonModifier) {
                    Text(action.label, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                OutlinedButton(onClick = action.onClick, modifier = buttonModifier) {
                    Text(action.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
