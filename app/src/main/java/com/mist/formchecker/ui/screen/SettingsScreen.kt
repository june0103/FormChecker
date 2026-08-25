package com.mist.formchecker.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.mist.formchecker.data.BodyInput
import com.mist.formchecker.data.BodyProfile
import com.mist.formchecker.poseengine.Sex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.ui.screen.workout.CalibrationPrep
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.OutlineVariant
import com.mist.formchecker.ui.theme.SurfaceVariant
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 설정.
 *
 * ## 지금 있는 것
 * 기준 완화, 내 정보(키·몸무게·나이·성별), 준비 시간. 기준 완화는 **판정에 실제로 영향을
 * 주는 유일한 설정**이다([RelaxedFormSection]). 내 정보는 소모 열량을 계산하기 위한 것이고
 * **넣지 않는 것이 정상 상태다.** 준비 시간은 운동 화면에 있던 선택 줄을 옮겨 온 것이다 —
 * 매번 같은 자리에서 찍는 사람에게는 그 줄이 화면만 차지했다.
 *
 * ## 순서는 "찾으러 올 순서"다
 * 기준 완화가 맨 위다. 세 항목 다 한 번 정하면 거의 안 바뀌지만, **이 화면을 여는 이유가
 * 되는 항목**은 다르다 — "자꾸 지적받는다"고 느낀 사람이 설정을 열면 그걸 찾는다. 준비
 * 시간은 맨 아래다([PrepSecondsSection]).
 *
 * ## 설명을 팝업에 넣는다
 * 항목마다 "왜 이 값을 고르나"를 문단으로 적어 두었더니 화면이 설명문 세 덩이가 됐고, 정작
 * 고를 버튼과 입력칸이 아래로 밀렸다. 설명은 [SettingsSectionHeader]의 ⓘ를 눌렀을 때만 뜬다.
 *
 * **운동 화면의 `InfoNote`와 다른 방식인 것이 의도다.** 그쪽은 폰을 세워두고 떨어져서 보므로
 * 눌러도 화면 안에서 펼쳐져야 하고(대화상자는 프리뷰를 덮는다), 여기는 폰을 손에 들고 보므로
 * 대화상자가 읽기 쉽고 세로 공간을 전혀 먹지 않는다.
 *
 * ## 팝업에 넣지 않는 것
 * **상태**는 항상 보인다 — "기본값이에요", "둘 다 넣어야 열량을 계산할 수 있어요". 설명은
 * 안 읽어도 되지만 상태는 지금 무엇이 되어 있는지를 말하므로, 숨기면 사용자가 고장으로 읽는다.
 * 같은 이유로 "선택"이라는 사실은 제목에 남긴다(`나이·성별 (선택)`) — 필수로 오해하면 빈 칸을
 * 억지로 채운다.
 *
 * ## 아직 없는 것
 * 설계문서 5장이 요구한 delegate(CPU/GPU)·해상도 선택은 **측정용 실험 노브**이고, 지금은
 * 개발용 운동 화면에 모델 전환만 있다. 세트 사이 휴식 시간은 세트 개념이 생길 때 여기
 * 붙는다(개편 목록 ③).
 *
 * ## 값을 되돌리는 버튼을 두지 않는다
 * 준비 시간은 기본값이 목록 안에 표시되고, 내 정보는 칸을 비우면 지워진다. 항목이 더
 * 늘면 그때 필요해진다.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prepSeconds by viewModel.prepSeconds.collectAsStateWithLifecycle()
    val bodyInput by viewModel.bodyInput.collectAsStateWithLifecycle()
    val relaxedForm by viewModel.relaxedForm.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineLarge)

        RelaxedFormSection(
            enabled = relaxedForm,
            onChange = viewModel::setRelaxedForm,
        )

        BodyProfileSection(
            input = bodyInput,
            onSaveHeight = viewModel::saveHeight,
            onSaveWeight = viewModel::saveWeight,
            onSaveAge = viewModel::saveAge,
            onSaveSex = viewModel::saveSex,
        )

        PrepSecondsSection(
            selected = prepSeconds,
            onSelect = viewModel::selectPrepSeconds,
        )

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
        ) { Text("뒤로", style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * 준비 시간 선택.
 *
 * ## 왜 맨 아래인가
 * **한 번 정하면 거의 안 바뀌는 값이다.** 삼각대 위치나 방 크기에 따라 사람마다 다르지만 한
 * 사람에게는 계속 같다 — 그것이 애초에 이 줄을 운동 화면에서 빼낸 이유였다. 이 화면을 여는
 * 이유는 보통 내 정보 쪽이므로, 준비 시간이 위에 있으면 매번 지나쳐야 하는 줄이 된다.
 *
 * 스크롤 없이 다 보이는 화면이라 순서가 접근성을 깎지도 않는다 — 더 늘어나서 스크롤이
 * 생기면 그때는 자주 쓰는 항목이 위에 있는 편이 더 중요해진다.
 */
@Composable
private fun PrepSecondsSection(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    SettingsSectionHeader(
        title = "준비 시간",
        // 왜 고를 수 있는지를 적는다. 이 값을 화면에서 설정으로 옮긴 이유가 바로
        // "사람마다 다르지만 한 사람에게는 거의 안 바뀐다"였다.
        detail = "기준 자세 재기를 누른 뒤 측정이 시작되기까지 기다리는 시간이에요. " +
            "버튼을 누른 자리에서 카메라 앞까지 걸어갈 시간입니다.\n\n" +
            "삼각대를 앞에 두고 화면에 손이 닿으면 \"바로\"가 맞고, 방 건너편에 " +
            "두었으면 10초도 짧아요.",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CalibrationPrep.OPTIONS.forEach { seconds ->
            val chosen = seconds == selected
            val label = if (seconds == 0) "바로" else "${seconds}초"
            val modifierFor = Modifier.weight(1f).height(TouchTarget.minSize)
            if (chosen) {
                Button(
                    onClick = { onSelect(seconds) },
                    modifier = modifierFor,
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(seconds) },
                    modifier = modifierFor,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }

    if (selected == CalibrationPrep.DEFAULT_SECONDS) {
        Text(
            text = "기본값이에요.",
            style = MaterialTheme.typography.labelSmall,
            color = LimeGreen,
        )
    }
}

/**
 * 자세 판정 기준 완화 토글.
 *
 * ## 왜 이 항목이 생겼나
 * 실제 사용자가 *"맞게 하고 있는 것 같은데 계속 주의를 주니까 거부감이 든다"*고 했다. 실측
 * 12세션 240 rep을 다시 계산하니 **정상 의도 세션에서도 기본 기준이 62%에 경고를 냈고**,
 * 완화 기준은 45%로 내리면서 무릎이 모이는 것을 여전히 잡는다. 근거와 한계는
 * `FormThresholds.RELAXED_KNEE_TOE_TOLERANCE` 주석에 있다.
 *
 * ## 숫자를 화면에 쓰지 않는다
 * 임계값(0.10 / 0.15)은 설계문서 5.2절에 따라 사용자 화면에 노출하지 않는다. 그 숫자는
 * 발목 간격으로 정규화한 무차원값이라 사용자가 판단에 쓸 수 없고, 보이면 "0.15가 뭔가"를
 * 묻게 된다. 개발 화면에는 그대로 띄운다(`KneeTolerance`).
 *
 * ## 켜짐/꺼짐을 글자로 쓴다
 * 스위치 위치만으로 상태를 전달하지 않는다 — 디자인시스템 34행이 색만으로 상태를 전달하지
 * 말라고 한 것과 같은 이유다. 떨어져서 보거나 색을 구분하기 어려우면 스위치 모양만으로는
 * 켜졌는지 알기 어렵다.
 */
@Composable
private fun RelaxedFormSection(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsSectionHeader(
        title = "기준 완화",
        detail = "자세를 지적하는 기준을 조금 넉넉하게 잡아요. 자세가 맞는데도 자꾸 " +
            "지적받는다고 느껴지면 켜 보세요.\n\n" +
            // 대화상자는 마크다운을 렌더링하지 않는다 — 강조 기호를 쓰면 별표가 그대로 찍힌다.
            "앉은 깊이와 무릎 방향 두 가지에 적용돼요. 깊이는 무릎 높이에 " +
            "조금 못 미쳐도 넘어가고, 무릎은 발끝에서 조금 벗어나도 넘어가요.\n\n" +
            "켜도 확실히 어긋난 자세는 그대로 알려드려요 — 무릎이 안쪽으로 모이는 것도 " +
            "계속 봐 드리고, 더 깊게 앉는 것은 어차피 문제가 아니라 그대로예요.\n\n" +
            "다만 살짝 어긋난 것은 넘어갈 수 있어요. 자세를 정확히 보고 싶을 때는 끄는 " +
            "편이 맞아요.\n\n" +
            "상체 숙임과 뒤꿈치 들림은 이 설정과 무관하게 그대로예요.",
    )

    Row(
        // **줄 전체가 과녁이다.** 스위치만 누를 수 있으면 폭 180px짜리 과녁이 되고, 실제로
        // 기기에서 첫 탭이 빗나갔다. `onCheckedChange = null`과 짝이어야 한다 — 둘 다 두면
        // 접근성 트리에 누를 수 있는 것이 두 개로 잡힌다.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget.minSize)
            .toggleable(
                value = enabled,
                onValueChange = onChange,
                role = Role.Switch,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            // 스위치 옆에 상태를 글자로 둔다. 스위치만으로는 켜졌는지 읽기 어렵다.
            text = if (enabled) "켜짐" else "꺼짐",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) LimeGreen else TextMuted,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = null,
            // ## 꺼짐 상태의 원이 안 보였다
            // M3 기본값은 꺼짐 thumb에 `outline`(#2E2E38)을 쓰는데, 트랙이
            // `surfaceVariant`(#24242E)라 **둘의 대비가 1.1:1**이다 — 이 다크 팔레트에서는
            // 원이 트랙에 묻힌다. 기기에서 실제로 안 보였다.
            //
            // 새 색을 만들지 않고 [TextMuted]를 쓴다. `#9A9AA5` vs `#24242E`는 **5.52:1**로
            // UI 컴포넌트 기준(WCAG 1.4.11 비텍스트 3:1)을 넘는다.
            //
            // [TextPrimary](13:1)를 쓰지 않은 이유: 꺼짐이 켜짐처럼 눈에 띄면 상태를
            // 반대로 읽는다. 보이기만 하면 되고 강조되면 안 된다.
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceVariant,
                // 트랙 경계도 한 단 밝게 — 원만 밝으면 트랙이 어디까지인지 안 보인다.
                uncheckedBorderColor = OutlineVariant,
            ),
        )
    }

    Text(
        text = if (enabled) {
            "앉은 깊이와 무릎 방향을 넉넉한 기준으로 봐요."
        } else {
            "기본 기준으로 봐요."
        },
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
    )
}

/**
 * 항목 제목 + 설명 팝업을 여는 ⓘ.
 *
 * 제목 줄에 아이콘을 같이 두는 이유: 아래에 붙이면 어느 항목의 설명인지 모호해지고, 항목이
 * 늘수록 아이콘만 줄줄이 남는다.
 *
 * @param detail 팝업 본문. 비워 두면 아이콘이 아예 안 나온다 — 눌러도 아무 일이 없는
 *   아이콘은 고장으로 읽힌다.
 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (detail.isNotBlank()) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = "$title 설명 보기") { showDetail = true }
                    // 접근성 최소 터치 타겟 (디자인시스템 68행). 아이콘 글리프만으로는
                    // 12sp짜리 과녁이 된다.
                    .heightIn(min = TouchTarget.minSize)
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // 색만으로 뜻을 전달하지 않는다 (디자인시스템 34행) — 글자를 함께 둔다.
                Text(
                    text = "ⓘ",
                    style = MaterialTheme.typography.titleLarge,
                    color = FeedbackInfo,
                )
                Text(
                    text = "설명",
                    style = MaterialTheme.typography.labelMedium,
                    color = FeedbackInfo,
                )
            }
        }
    }

    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) {
                    Text("닫기", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

/**
 * 키·몸무게 입력.
 *
 * ## 왜 입력하나
 * 소모 열량을 계산하려면 둘 다 필요하다 — 기초대사량 식(Mifflin-St Jeor)이 키와 몸무게를
 * 함께 쓴다. **입력하지 않는 것이 정상 상태다**: 넣지 않으면 열량 항목만 빠지고 나머지는
 * 그대로 동작한다.
 *
 * ## 입력 중인 글자를 저장하지 않는다
 * "17"까지 친 순간 키가 17cm로 저장되면 안 된다. 화면이 글자를 들고 있고, 숫자로 읽히고
 * 범위 안일 때만 저장한다. 비우면 지운다 — 한 번 넣으면 못 지우는 신체 정보는 사용자가
 * 되돌릴 수 없다.
 *
 * ## 칸마다 자기 값만 저장한다
 * 예전에는 키·몸무게를 한 번에 저장했고, 그래서 **한쪽 칸이 비어 있으면 다른 쪽 저장이 그
 * 값을 지웠다.** 키 175만 저장된 상태에서 몸무게를 입력하면 키가 사라졌다(실기기 재현).
 * 지금은 칸이 각자 자기 값만 쓴다.
 */
@Composable
private fun BodyProfileSection(
    input: BodyInput?,
    onSaveHeight: (Float?) -> Unit,
    onSaveWeight: (Float?) -> Unit,
    onSaveAge: (Int?) -> Unit,
    onSaveSex: (Sex?) -> Unit,
) {
    // 디스크를 읽고 나서 한 번만 채운다. key가 input 자체면 사용자가 타이핑하는 중에
    // 리셋되고, key를 안 주면 첫 프레임의 "아직 못 읽음"(null) 상태로 굳는다 — 그게 저장된
    // 값이 안 보이던 원인이었다.
    val loaded = input != null
    var height by remember(loaded) { mutableStateOf(input?.heightCm.toField()) }
    var weight by remember(loaded) { mutableStateOf(input?.weightKg.toField()) }
    var age by remember(loaded) { mutableStateOf(input?.age?.toString() ?: "") }

    SettingsSectionHeader(
        title = "내 정보",
        detail = "키와 몸무게를 넣으면 운동이 끝난 뒤 소모 열량을 대략 알려드려요. " +
            "넣지 않으면 열량만 빠지고 나머지는 그대로예요.\n\n" +
            "둘 다 필요한 이유는 기초대사량을 구하는 식이 키와 몸무게를 함께 쓰기 " +
            "때문이에요. 하나만 넣으면 계산이 성립하지 않아요.\n\n" +
            "넣은 값은 이 기기에만 저장되고, 칸을 비우면 지워져요.",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = height,
            onValueChange = { height = it.filterMeasure(); onSaveHeight(height.toMeasure()) },
            label = { Text("키 (cm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = height.isNotEmpty() && height.toMeasure() == null,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filterMeasure(); onSaveWeight(weight.toMeasure()) },
            label = { Text("몸무게 (kg)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = weight.isNotEmpty() && weight.toMeasure() == null,
            modifier = Modifier.weight(1f),
        )
    }

    // 상태는 저장된 값으로 판단한다 — 입력칸 글자로 보면 범위를 벗어나 저장되지 않은
    // 값("300")도 계산되는 것처럼 보인다.
    val canCalculate = input?.profile != null
    Text(
        text = when {
            canCalculate -> "열량을 계산할 수 있어요."
            height.isEmpty() && weight.isEmpty() -> "지금은 열량을 계산하지 않아요."
            // 하나만 넣은 상태. 왜 아직 안 되는지 말해야 한다 — 넣었는데 안 된다고
            // 읽히면 고장으로 보인다.
            else -> "둘 다 넣어야 열량을 계산할 수 있어요."
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (canCalculate) LimeGreen else TextMuted,
    )

    // ── 선택: 나이·성별 ─────────────────────────────────────
    //
    // 열량 계산이 쓰는 Mifflin-St Jeor 기초대사량 식은 나이와 성별을 요구한다. 없으면
    // 기본값(30세·남성)을 쓰고 결과 화면이 그 사실을 밝힌다 — 필수로 만들지 않은 이유는
    // 키·몸무게만으로도 값이 나오는 편이 낫기 때문이다.
    //
    // "(선택)"을 제목에 남기는 이유: 팝업에 넣으면 안 열어 본 사람은 필수로 읽는다.
    SettingsSectionHeader(
        title = "나이·성별 (선택)",
        detail = "기초대사량을 구하는 식이 나이와 성별도 씁니다. 넣으면 열량이 더 " +
            "정확해져요.\n\n" +
            "넣지 않아도 열량은 계산돼요 — 30세·남성으로 가정하고, 결과 화면이 " +
            "가정을 썼다는 사실을 함께 알려드려요.",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = age,
            onValueChange = {
                age = it.filter { c -> c.isDigit() }.take(3)
                onSaveAge(age.toIntOrNull())
            },
            label = { Text("나이") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = age.isNotEmpty() && age.toIntOrNull()
                ?.let { !BodyProfile.ageValid(it) } ?: true,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Sex.entries.forEach { option ->
                val selected = input?.sex == option
                val label = if (option == Sex.MALE) "남성" else "여성"
                val press = { onSaveSex(if (selected) null else option) }
                if (selected) {
                    Button(
                        onClick = press,
                        modifier = Modifier.weight(1f).height(TouchTarget.minSize),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                } else {
                    OutlinedButton(
                        onClick = press,
                        modifier = Modifier.weight(1f).height(TouchTarget.minSize),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** 숫자와 소수점만 남긴다. 키보드가 숫자여도 붙여넣기로 글자가 들어올 수 있다. */
private fun String.filterMeasure(): String =
    filter { it.isDigit() || it == '.' }.take(5)

/**
 * 0보다 큰 숫자면 값, 아니면 null. 빈 칸도 null이다(지우기).
 *
 * 범위 확인은 여기서 하지 않는다 — 저장을 막는 것은 `SettingsViewModel`의 일이고, 화면은
 * "숫자로 읽히는가"까지만 본다. 범위를 두 곳에서 보면 한쪽만 고쳐진다.
 */
private fun String.toMeasure(): Float? = toFloatOrNull()?.takeIf { it > 0f }

private fun Float?.toField(): String =
    this?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: ""
