package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.mist.formchecker.poseengine.Keypoint
import com.mist.formchecker.poseengine.KeypointType
import com.mist.formchecker.poseengine.Pose

/** 스켈레톤으로 이을 관절 쌍. 하체를 굵게 그리기 위해 상체/하체를 나눠 둔다. */
private val UPPER_BODY = listOf(
    KeypointType.LEFT_SHOULDER to KeypointType.RIGHT_SHOULDER,
    KeypointType.LEFT_SHOULDER to KeypointType.LEFT_ELBOW,
    KeypointType.LEFT_ELBOW to KeypointType.LEFT_WRIST,
    KeypointType.RIGHT_SHOULDER to KeypointType.RIGHT_ELBOW,
    KeypointType.RIGHT_ELBOW to KeypointType.RIGHT_WRIST,
    KeypointType.LEFT_SHOULDER to KeypointType.LEFT_HIP,
    KeypointType.RIGHT_SHOULDER to KeypointType.RIGHT_HIP,
)

/** 스쿼트 판정에 실제로 쓰이는 선. 눈으로 각도를 확인할 수 있게 강조해서 그린다. */
private val LOWER_BODY = listOf(
    KeypointType.LEFT_HIP to KeypointType.RIGHT_HIP,
    KeypointType.LEFT_HIP to KeypointType.LEFT_KNEE,
    KeypointType.LEFT_KNEE to KeypointType.LEFT_ANKLE,
    KeypointType.RIGHT_HIP to KeypointType.RIGHT_KNEE,
    KeypointType.RIGHT_KNEE to KeypointType.RIGHT_ANKLE,
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

        fun drawBone(pairs: List<Pair<KeypointType, KeypointType>>, width: Float, alpha: Float) {
            pairs.forEach { (from, to) ->
                if (!pose.isReliable(from, minConfidence) || !pose.isReliable(to, minConfidence)) {
                    return@forEach
                }
                drawLine(
                    color = SkeletonColor.copy(alpha = alpha),
                    start = pose[from].toOffset(),
                    end = pose[to].toOffset(),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
        }

        drawBone(UPPER_BODY, width = 4.dp.toPx(), alpha = 0.5f)
        drawBone(LOWER_BODY, width = 7.dp.toPx(), alpha = 0.9f)

        pose.keypoints.forEach { keypoint ->
            if (keypoint.confidence < minConfidence) return@forEach
            drawCircle(
                color = SkeletonColor,
                radius = 5.dp.toPx(),
                center = keypoint.toOffset(),
            )
        }
    }
}

/**
 * 디자인시스템 87행: 관절점은 라임, 연결선은 50% 불투명도.
 *
 * `MaterialTheme.colorScheme.primary`를 쓰지 않고 상수로 둔 이유는 이 색이 "테마 색"이
 * 아니라 카메라 영상 위 판독성을 위해 고정돼야 하는 값이기 때문이다. 디자인시스템도
 * 스켈레톤 색을 별도 규칙으로 못박고 있다.
 */
private val SkeletonColor = Color(0xFFC6FF00)
