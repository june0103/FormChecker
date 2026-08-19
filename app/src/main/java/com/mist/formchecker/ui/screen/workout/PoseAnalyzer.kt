package com.mist.formchecker.ui.screen.workout

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mist.formchecker.poseengine.JointAngles
import com.mist.formchecker.poseengine.KneeAngles
import com.mist.formchecker.poseengine.Pose
import com.mist.formchecker.poseengine.PoseEngine

/**
 * 한 프레임의 분석 결과.
 *
 * [frameWidth]/[frameHeight]는 **회전을 적용한 뒤**의 크기다. 키포인트의 정규화 좌표가
 * 이 크기를 기준으로 하므로, 오버레이를 그릴 때와 각도를 계산할 때 같은 값을 써야 한다.
 */
data class PoseResult(
    val pose: Pose,
    val knees: KneeAngles,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * CameraX 프레임을 [PoseEngine]에 흘려보내는 분석기.
 *
 * ## 회전 처리
 * 센서는 기기 방향과 무관하게 고정된 방향으로 프레임을 준다. `rotationDegrees`를 적용하지
 * 않으면 세로로 든 기기에서 스켈레톤이 90도 누워서 그려지고, 각도 계산도 함께 틀어진다.
 * 기기마다 센서 장착 방향이 달라 특정 기기에서만 발생하는 종류의 버그라 처음부터 넣는다.
 *
 * ## 미러링을 여기서 하지 않는 이유 (중요)
 * 전면 카메라의 프리뷰는 거울처럼 좌우 반전돼 보이지만, `ImageAnalysis`가 주는 프레임은
 * 반전되지 않은 원본이다. **여기서 굳이 반전시키면 안 된다.** MoveNet은 키포인트를 해부학적
 * 기준(그 사람의 실제 왼쪽/오른쪽)으로 라벨링하는데, 반전된 이미지를 넣으면 좌우 라벨이
 * 뒤바뀌어 나온다. 그러면 설계문서 3.2절 5번의 무릎 정렬(knee valgus) 판정이 좌우가 뒤집힌
 * 채로 조용히 틀린 결과를 낸다.
 *
 * 따라서 **추론은 반전 없이** 하고, 화면에 그릴 때만 좌표를 뒤집는다
 * ([SkeletonOverlay]의 `mirrored` 파라미터).
 */
class PoseAnalyzer(
    private val engine: PoseEngine,
    private val onResult: (PoseResult) -> Unit,
    private val onFrameDropped: () -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val matrix = Matrix()

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val upright = runCatching { proxy.toUprightBitmap() }.getOrNull()
            if (upright == null) {
                onFrameDropped()
                return
            }

            val pose = engine.analyze(upright)
            if (pose == null) {
                // 설계문서 6장: 실패한 프레임은 드롭 카운트에 반영하고 다음 프레임으로 넘어간다.
                onFrameDropped()
                return
            }

            onResult(
                PoseResult(
                    pose = pose,
                    knees = JointAngles.knees(
                        pose = pose,
                        // 정규화 좌표계는 x축과 y축의 "1"이 서로 다른 픽셀 길이를 뜻하는
                        // 비등방 좌표계다. 실제 종횡비를 넘겨야 각도가 맞는다.
                        frameAspectRatio = upright.width.toFloat() / upright.height,
                    ),
                    frameWidth = upright.width,
                    frameHeight = upright.height,
                ),
            )
        }
    }

    /** 센서 방향을 보정해 화면에서 보이는 것과 같은 방향의 비트맵을 만든다. */
    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val source = toBitmap()
        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return source

        matrix.reset()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
