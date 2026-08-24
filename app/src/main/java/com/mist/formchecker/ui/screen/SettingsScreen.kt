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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.mist.formchecker.data.BodyProfile
import com.mist.formchecker.poseengine.Sex
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
 * 준비 시간과 내 정보(키·몸무게). 준비 시간은 운동 화면에 있던 선택 줄을 옮겨 온 것이다 —
 * 매번 같은 자리에서 찍는 사람에게는 그 줄이 화면만 차지했다. 내 정보는 소모 열량을
 * 계산하기 위한 것이고, **넣지 않는 것이 정상 상태다.**
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
    val bodyProfile by viewModel.bodyProfile.collectAsStateWithLifecycle()

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

        BodyProfileSection(
            saved = bodyProfile,
            onSave = viewModel::saveBodyProfile,
            onSaveAge = viewModel::saveAge,
            onSaveSex = viewModel::saveSex,
        )

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
        ) { Text("뒤로", style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * 키·몸무게 입력.
 *
 * ## 왜 입력하나
 * 소모 열량을 계산하려면 둘 다 필요하다 — 몸무게는 들어 올리는 질량이고, 키는 엉덩이가
 * 실제로 몇 cm 내려갔는지를 환산하는 데 쓴다. **입력하지 않는 것이 정상 상태다**:
 * 넣지 않으면 열량 항목만 빠지고 나머지는 그대로 동작한다.
 *
 * ## 입력 중인 글자를 저장하지 않는다
 * "17"까지 친 순간 키가 17cm로 저장되면 안 된다. 화면이 글자를 들고 있고, 숫자로 읽히고
 * 범위 안일 때만 저장한다. 비우면 지운다 — 한 번 넣으면 못 지우는 신체 정보는 사용자가
 * 되돌릴 수 없다.
 */
@Composable
private fun BodyProfileSection(
    saved: BodyProfile?,
    onSave: (heightCm: Float?, weightKg: Float?) -> Unit,
    onSaveAge: (Int?) -> Unit,
    onSaveSex: (Sex?) -> Unit,
) {
    // 저장된 값이 바뀌면(다른 화면에서 지웠다면) 입력칸도 따라간다. key로 saved를 주면
    // 사용자가 타이핑하는 중에 리셋되므로, 처음 한 번만 채운다.
    var height by remember(saved == null) { mutableStateOf(saved?.heightCm.toField()) }
    var weight by remember(saved == null) { mutableStateOf(saved?.weightKg.toField()) }
    var age by remember(saved == null) { mutableStateOf(saved?.age?.toString() ?: "") }

    fun commit() = onSave(height.toMeasure(), weight.toMeasure())

    Text("내 정보", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "키와 몸무게를 넣으면 운동이 끝난 뒤 소모 열량을 대략 알려드려요. " +
            "넣지 않으면 열량만 빠지고 나머지는 그대로예요.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = height,
            onValueChange = { height = it.filterMeasure(); commit() },
            label = { Text("키 (cm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = height.isNotEmpty() && height.toMeasure() == null,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filterMeasure(); commit() },
            label = { Text("몸무게 (kg)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = weight.isNotEmpty() && weight.toMeasure() == null,
            modifier = Modifier.weight(1f),
        )
    }

    Text(
        text = when {
            saved != null -> "열량을 계산할 수 있어요."
            height.isEmpty() && weight.isEmpty() -> "지금은 열량을 계산하지 않아요."
            // 하나만 넣은 상태. 왜 아직 안 되는지 말해야 한다 — 넣었는데 안 된다고
            // 읽히면 고장으로 보인다.
            else -> "둘 다 넣어야 열량을 계산할 수 있어요."
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (saved != null) LimeGreen else TextMuted,
    )

    // ── 선택: 나이·성별 ─────────────────────────────────────
    //
    // 열량 계산이 쓰는 Mifflin-St Jeor 기초대사량 식은 나이와 성별을 요구한다. 없으면
    // 기본값(30세·남성)을 쓰고 결과 화면이 그 사실을 밝힌다 — 필수로 만들지 않은 이유는
    // 키·몸무게만으로도 값이 나오는 편이 낫기 때문이다.
    Text(
        text = "나이와 성별은 선택이에요. 넣으면 열량이 더 정확해져요 — " +
            "기초대사량 식이 둘을 씁니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
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
                val selected = saved?.sex == option
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
