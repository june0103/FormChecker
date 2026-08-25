package com.mist.formchecker.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 화면 맨 위 한 줄 — **뒤로가기 아이콘 + 화면 제목**. 모든 화면에서 같은 자리다.
 *
 * ## 왜 하단 버튼에서 옮겼나
 * 예전에는 화면마다 하단에 전체폭 `뒤로` 버튼이 있었다. 세 가지가 문제였다.
 * 1. **자리가 화면마다 달랐다** — 설정은 스크롤 맨 아래, 개발 화면은 `전면/뒤로/종료`
 *    한 줄 가운데, 수집 화면은 단계마다 다른 자리였다. 나가는 방법을 화면마다 다시 찾아야 했다.
 * 2. 사용자용 운동 화면만 이미 좌상단 아이콘이었다 — **한 앱 안에서 두 규칙이 공존**했다.
 * 3. 하단 전체폭 버튼이 세로 공간을 차지했다. 결과 화면에서 실제로 잘린 적이 있다.
 *
 * ## 제목이 왜 여기로 왔나
 * 제목이 스크롤 안에 있으면 **조금만 내려도 사라진다** — 어느 화면인지 알려주는 글자가
 * 아이콘과 떨어져 따로 스크롤됐다. 한 줄로 합치면 제목이 항상 보이고, 아이콘 옆에 있어
 * "여기서 나가면 이 화면을 떠난다"가 한눈에 읽힌다. 세로 한 줄도 줄어든다.
 *
 * ## 아이콘 아래 글자를 뺐다
 * 꺾쇠 아이콘은 설명이 필요 없을 만큼 굳은 관습이고, 옆에 제목이 붙으면서 `뒤로`라는
 * 글자가 제목과 나란히 겹쳐 읽혔다. **스크린 리더에는 그대로 나간다** —
 * [IconAction]이 `contentDescription`으로 붙인다. 반면 카메라 전환처럼 아이콘만으로는
 * 결과를 알 수 없는 버튼은 글자를 유지한다([IconAction]의 `showLabel`).
 *
 * ## 좌표를 맞추는 방법
 * 이 컴포넌트가 **바깥 여백까지 포함**한다([Spacing.sm]) — 화면이 자기 패딩 안에 넣으면
 * 그만큼 밀리므로, **화면 최상단에 패딩 없는 자리**에 놓아야 한다. 스크롤 안에 넣어도 안 된다.
 *
 * 카메라 화면(운동·수집)은 이 줄 대신 [IconAction]을 HUD에 직접 얹지만 좌표는 같다:
 * HUD 상단의 가로 패딩이 [Spacing.sm]이고 [IconAction] 안쪽 패딩이 [Spacing.sm]이라
 * 여기와 마찬가지로 화면 가장자리에서 16dp 지점에 글리프가 놓인다. 둘 중 하나를 바꾸면
 * 나머지도 함께 봐야 한다.
 *
 * @param title 없으면 아이콘만 있는 줄이 된다.
 * @param backLabel 접근성 이름. 수집 화면처럼 "나가기"가 더 맞는 곳이 있어 고정하지
 *   않았다 — **자리는 같게, 말은 맞게.**
 * @param trailing 오른쪽 끝에 붙는 것(기록 화면의 업로드 대기 배지). 제목과 같은 줄에
 *   두면 세로 한 줄을 아낀다.
 */
@Composable
fun ScreenHeader(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    backLabel: String = "뒤로",
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            label = backLabel,
            onClick = onBack,
            showLabel = false,
            glyph = { drawBackGlyph(it) },
        )
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                // 제목이 길어져도 배지를 밀어내지 않는다. 줄바꿈을 허용하면 화면마다
                // 이 줄의 높이가 달라져 아이콘 자리가 어긋난다.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = Spacing.xs),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
    }
}

/**
 * 그림 아이콘 하나로 된 버튼. 필요하면 아래에 글자를 붙인다.
 *
 * 카메라 위에 얹어도 읽히도록 만든 것이라(굵은 선, 밝은 색) 일반 배경에서도 그대로 쓴다 —
 * 화면마다 다른 아이콘을 두면 같은 동작이 다르게 보인다.
 *
 * **글자를 붙일지는 아이콘이 스스로 말하는지로 정한다.** 디자인시스템 34행이 색만으로
 * 상태를 전달하지 말라고 한 것과 같은 이유로, 뜻이 갈리는 아이콘은 그림만으로 두지 않는다 —
 * 카메라 전환은 지금이 전면인지 후면인지를 그림으로 구분할 수 없어 글자가 필요하고,
 * 뒤로가기 꺾쇠는 그렇지 않다. 어느 쪽이든 스크린 리더에는 [label]이 나간다.
 */
@Composable
internal fun IconAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    glyph: DrawScope.(Color) -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            // 접근성 최소 터치 타겟 (디자인시스템 68행). `clickable`보다 먼저 둬야
            // 늘어난 높이가 과녁에 포함된다. 글자를 뺀 아이콘은 이 값이 곧 크기가 된다.
            .heightIn(min = TouchTarget.minSize)
            .clickable(onClickLabel = label, onClick = onClick)
            // 아이콘이 그림이라 이름을 따로 붙인다 — 아래 라벨 텍스트는 시각 정보이고,
            // 스크린 리더에는 이 값이 나간다.
            //
            // 접근성 트리에서는 클릭 노드와 이름 노드가 따로 잡힌다(기기에서 확인).
            // 이 앱의 다른 버튼도 모두 같은 모양이라 Compose가 병합 노드를 그렇게
            // 내보내는 것으로 보고 그대로 뒀다.
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(modifier = Modifier.size(IconActionSize)) { glyph(TextPrimary) }
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
            )
        }
    }
}

/** 아이콘 한 변. 터치 타겟(48dp)보다 작다 — 여백이 과녁을 채운다. */
private val IconActionSize = 26.dp

/** 선 두께 비율. 카메라 영상 위에서 사라지지 않을 만큼 굵게 둔다. */
private const val GLYPH_STROKE = 0.12f

/** 왼쪽을 가리키는 꺾쇠. 뒤로가기. */
internal fun DrawScope.drawBackGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.62f, h * 0.16f)
        lineTo(w * 0.34f, h * 0.50f)
        lineTo(w * 0.62f, h * 0.84f)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = w * GLYPH_STROKE,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

/** [GLYPH_STROKE]를 다른 글리프에서도 쓸 수 있게 연다. 선 두께가 갈리면 아이콘이 따로 논다. */
internal val DrawScope.glyphStrokeWidth: Float get() = size.width * GLYPH_STROKE
