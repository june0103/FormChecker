package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mist.formchecker.poseengine.Keypoint
import com.mist.formchecker.poseengine.KeypointType
import com.mist.formchecker.poseengine.Pose

/**
 * 뼈대 하나를 그리는 규칙.
 *
 * 좌우는 **같은 라임 계열의 밝기 차이**로만 구분한다. 새 색을 쓰지 않는 이유는
 * 디자인시스템 32행 때문이다 — 이 앱에서 색이 의미를 갖는 자리는 "자세가 틀렸다"를
 * 알리는 경고(Warning/Danger)이고, 좌우 구분에 색을 써버리면 나중에 무릎 정렬 경고가
 * 떴을 때 정작 중요한 신호가 묻힌다.
 */
private enum class Side { LEFT, RIGHT, CENTER }

private data class Bone(
    val from: KeypointType,
    val to: KeypointType,
    val side: Side,
    /** 스쿼트 판정에 직접 쓰이는 선인지. 굵기로 강조한다. */
    val primary: Boolean = false,
)

private val BONES = listOf(
    // 하체 — 설계문서 3.2절의 힙-무릎-발목 각도를 이루는 선. 판정의 핵심이라 굵게.
    Bone(KeypointType.LEFT_HIP, KeypointType.LEFT_KNEE, Side.LEFT, primary = true),
    Bone(KeypointType.LEFT_KNEE, KeypointType.LEFT_ANKLE, Side.LEFT, primary = true),
    Bone(KeypointType.RIGHT_HIP, KeypointType.RIGHT_KNEE, Side.RIGHT, primary = true),
    Bone(KeypointType.RIGHT_KNEE, KeypointType.RIGHT_ANKLE, Side.RIGHT, primary = true),

    // 몸통 — 상체 기울기 판정에 쓸 선.
    Bone(KeypointType.LEFT_SHOULDER, KeypointType.LEFT_HIP, Side.LEFT),
    Bone(KeypointType.RIGHT_SHOULDER, KeypointType.RIGHT_HIP, Side.RIGHT),

    // 팔 — 판정에 쓰이지 않는다. 사람 형태를 알아보기 위한 보조선.
    Bone(KeypointType.LEFT_SHOULDER, KeypointType.LEFT_ELBOW, Side.LEFT),
    Bone(KeypointType.LEFT_ELBOW, KeypointType.LEFT_WRIST, Side.LEFT),
    Bone(KeypointType.RIGHT_SHOULDER, KeypointType.RIGHT_ELBOW, Side.RIGHT),
    Bone(KeypointType.RIGHT_ELBOW, KeypointType.RIGHT_WRIST, Side.RIGHT),

    // 발 — Halpe26 모델만 출력한다. MoveNet에서는 confidence 0이라 자동으로 그려지지 않는다.
    // 발뒤꿈치 들림 판정의 근거가 될 부위라 하체와 같은 굵기로 강조한다.
    Bone(KeypointType.LEFT_ANKLE, KeypointType.LEFT_HEEL, Side.LEFT, primary = true),
    Bone(KeypointType.LEFT_HEEL, KeypointType.LEFT_BIG_TOE, Side.LEFT, primary = true),
    Bone(KeypointType.LEFT_BIG_TOE, KeypointType.LEFT_SMALL_TOE, Side.LEFT),
    Bone(KeypointType.RIGHT_ANKLE, KeypointType.RIGHT_HEEL, Side.RIGHT, primary = true),
    Bone(KeypointType.RIGHT_HEEL, KeypointType.RIGHT_BIG_TOE, Side.RIGHT, primary = true),
    Bone(KeypointType.RIGHT_BIG_TOE, KeypointType.RIGHT_SMALL_TOE, Side.RIGHT),
)

/**
 * 어깨선·힙선은 사지가 아니라 **수평 기준선**이다.
 *
 * 이 선들은 기울어짐 자체가 정보다 — 힙선이 기울면 좌우 체중 불균형을 뜻한다.
 * 성격이 다르므로 색이 아니라 형태(얇은 점선)로 구분해 사지와 섞이지 않게 한다.
 */
private val REFERENCE_LINES = listOf(
    KeypointType.LEFT_SHOULDER to KeypointType.RIGHT_SHOULDER,
    KeypointType.LEFT_HIP to KeypointType.RIGHT_HIP,
)

/**
 * 카메라 프리뷰 위에 스켈레톤을 그린다.
 *
 * 이 컴포저블은 **프리뷰가 표시하는 영역과 정확히 같은 크기·위치**에 놓여야 한다.
 * 분석 프레임과 프리뷰의 종횡비가 다르거나 프리뷰가 잘려서 표시되면 스켈레톤이 몸에서
 * 어긋나는데, 크래시가 없어 "모델이 부정확한가?"로 오진하기 쉽다. 호출부에서 카메라
 * 컨테이너를 분석 프레임과 같은 종횡비로 고정해 이 문제를 원천 차단한다.
 *
 * @param mirrored 전면 카메라처럼 프리뷰가 좌우 반전돼 보일 때 true. 추론은 반전 없이
 *   수행하므로(해부학적 좌우 라벨을 보존하기 위해 — [PoseAnalyzer] 참고) 화면에 그릴
 *   때만 x를 뒤집어 프리뷰와 맞춘다.
 *
 *   좌우 밝기가 다르므로 이 값이 맞는지 눈으로 확인할 수 있다. 왼팔을 들었을 때 밝은
 *   쪽이 따라 올라가면 정상이고, 반대편이 밝으면 라벨이 뒤집힌 것이다.
 */
@Composable
fun SkeletonOverlay(
    pose: Pose?,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
    minConfidence: Float = Pose.MIN_CONFIDENCE,
) {
    Canvas(modifier = modifier) {
        if (pose == null) return@Canvas

        fun Keypoint.toOffset(): Offset {
            val px = if (mirrored) 1f - x else x
            return Offset(px * size.width, y * size.height)
        }

        fun isVisible(vararg types: KeypointType) =
            types.all { pose.isReliable(it, minConfidence) }

        // 기준선을 먼저 깔아 사지 아래에 놓는다.
        REFERENCE_LINES.forEach { (from, to) ->
            if (!isVisible(from, to)) return@forEach
            drawLine(
                color = SkeletonColor.copy(alpha = 0.45f),
                start = pose[from].toOffset(),
                end = pose[to].toOffset(),
                strokeWidth = 2.dp.toPx(),
                pathEffect = ReferenceDash,
            )
        }

        BONES.forEach { bone ->
            if (!isVisible(bone.from, bone.to)) return@forEach
            drawLine(
                color = bone.side.color(),
                start = pose[bone.from].toOffset(),
                end = pose[bone.to].toOffset(),
                strokeWidth = if (bone.primary) 7.dp.toPx() else 4.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = if (bone.primary) 0.95f else 0.6f,
            )
        }

        pose.keypoints.forEach { keypoint ->
            if (keypoint.confidence < minConfidence) return@forEach
            // Head/Neck/HipCenter는 코·어깨중점·힙중점과 겹쳐 그려져 화면만 지저분해진다.
            if (keypoint.type in HIDDEN_POINTS) return@forEach
            val center = keypoint.toOffset()
            val side = keypoint.type.side()
            val radius = if (keypoint.type.isPrimary()) 6.dp.toPx() else 4.dp.toPx()

            drawCircle(color = side.color(), radius = radius, center = center)
            // 어두운 쪽(오른쪽) 점이 배경과 뭉개지지 않도록 테두리를 얹는다.
            if (side == Side.RIGHT) {
                drawCircle(
                    color = SkeletonColor.copy(alpha = 0.7f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/** 중점 성격이라 다른 점과 겹쳐 그려지는 키포인트. Halpe26에만 존재한다. */
private val HIDDEN_POINTS = setOf(
    KeypointType.HEAD, KeypointType.NECK, KeypointType.HIP_CENTER,
)

private fun KeypointType.side(): Side = when (this) {
    KeypointType.LEFT_SHOULDER, KeypointType.LEFT_ELBOW, KeypointType.LEFT_WRIST,
    KeypointType.LEFT_HIP, KeypointType.LEFT_KNEE, KeypointType.LEFT_ANKLE,
    KeypointType.LEFT_EYE, KeypointType.LEFT_EAR,
    KeypointType.LEFT_BIG_TOE, KeypointType.LEFT_SMALL_TOE, KeypointType.LEFT_HEEL,
    -> Side.LEFT

    KeypointType.RIGHT_SHOULDER, KeypointType.RIGHT_ELBOW, KeypointType.RIGHT_WRIST,
    KeypointType.RIGHT_HIP, KeypointType.RIGHT_KNEE, KeypointType.RIGHT_ANKLE,
    KeypointType.RIGHT_EYE, KeypointType.RIGHT_EAR,
    KeypointType.RIGHT_BIG_TOE, KeypointType.RIGHT_SMALL_TOE, KeypointType.RIGHT_HEEL,
    -> Side.RIGHT

    KeypointType.NOSE, KeypointType.HEAD, KeypointType.NECK, KeypointType.HIP_CENTER,
    -> Side.CENTER
}

/** 스쿼트 판정에 직접 쓰이는 관절. 점을 크게 그려 눈에 띄게 한다. */
private fun KeypointType.isPrimary(): Boolean = this in setOf(
    KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
    KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
    KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE,
    KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL,
)

private fun Side.color(): Color = when (this) {
    Side.LEFT -> SkeletonColor
    Side.RIGHT -> SkeletonColorDim
    Side.CENTER -> SkeletonColor
}

private val ReferenceDash: PathEffect =
    PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)

/**
 * 디자인시스템 87행: 관절점·연결선은 라임 계열로 고정한다.
 *
 * `MaterialTheme.colorScheme.primary`를 쓰지 않고 상수로 둔 이유는 이 색이 "테마 색"이
 * 아니라 카메라 영상 위 판독성을 위해 고정돼야 하는 값이기 때문이다.
 *
 * 좌우는 팔레트의 `LimeGreen`/`LimeGreenDim` 밝기 차이로만 나눈다 — 새 색을 도입하면
 * 나중에 자세 경고(Warning 오렌지)를 얹을 때 화면이 산만해져 경고가 묻힌다.
 */
private val SkeletonColor = Color(0xFFC6FF00)
private val SkeletonColorDim = Color(0xFF8FB800)
