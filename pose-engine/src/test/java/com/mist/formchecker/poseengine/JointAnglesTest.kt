package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JointAnglesTest {

    /** 지정한 키포인트만 좌표를 넣고 나머지는 신뢰도 0으로 채운 포즈를 만든다. */
    private fun poseOf(
        vararg points: Pair<KeypointType, Triple<Float, Float, Float>>,
    ): Pose {
        val map = points.toMap()
        return Pose(
            keypoints = List(KeypointType.COUNT) { i ->
                val type = KeypointType.of(i)
                val p = map[type]
                Keypoint(type, p?.first ?: 0f, p?.second ?: 0f, p?.third ?: 0f)
            },
            inferenceTimeNanos = 0L,
        )
    }

    private fun leg(hip: Pair<Float, Float>, knee: Pair<Float, Float>, ankle: Pair<Float, Float>) =
        poseOf(
            KeypointType.LEFT_HIP to Triple(hip.first, hip.second, 0.9f),
            KeypointType.LEFT_KNEE to Triple(knee.first, knee.second, 0.9f),
            KeypointType.LEFT_ANKLE to Triple(ankle.first, ankle.second, 0.9f),
        )

    /** 힙-무릎-발목이 일직선이면 180도 — 설계문서 3.2절의 STANDING 상태에 해당한다. */
    @Test
    fun `똑바로 선 다리는 180도에 가깝다`() {
        val pose = leg(hip = 0.5f to 0.2f, knee = 0.5f to 0.5f, ankle = 0.5f to 0.8f)

        val angle = JointAngles.knees(pose, frameAspectRatio = 1f).left
        assertNotNull(angle)
        assertEquals(180f, angle!!, 1f)
    }

    /** 무릎을 90도로 굽힌 자세. */
    @Test
    fun `직각으로 굽힌 무릎은 90도다`() {
        val pose = leg(hip = 0.5f to 0.2f, knee = 0.5f to 0.5f, ankle = 0.8f to 0.5f)

        val angle = JointAngles.knees(pose, frameAspectRatio = 1f).left
        assertEquals(90f, angle!!, 1f)
    }

    /**
     * 종횡비 보정이 실제로 동작하는지 확인한다.
     *
     * 같은 정규화 좌표라도 프레임 종횡비가 다르면 실제 각도는 달라야 한다. 두 값이
     * 같게 나온다면 보정이 적용되지 않은 것이고, 그 경우 기기 카메라 비율에 따라
     * 같은 스쿼트가 다른 각도로 측정된다.
     */
    @Test
    fun `종횡비에 따라 각도가 달라진다`() {
        val pose = leg(hip = 0.5f to 0.3f, knee = 0.5f to 0.5f, ankle = 0.7f to 0.6f)

        val square = JointAngles.knees(pose, frameAspectRatio = 1f).left!!
        val wide = JointAngles.knees(pose, frameAspectRatio = 16f / 9f).left!!

        assertTrue(
            "종횡비 보정이 각도에 반영되어야 한다 (1:1=$square, 16:9=$wide)",
            kotlin.math.abs(square - wide) > 1f,
        )
    }

    /** 설계문서 3.2절 2번: 신뢰도가 낮은 키포인트로 계산한 각도는 내놓지 않는다. */
    @Test
    fun `신뢰도가 낮으면 각도를 계산하지 않는다`() {
        val pose = poseOf(
            KeypointType.LEFT_HIP to Triple(0.5f, 0.2f, 0.9f),
            KeypointType.LEFT_KNEE to Triple(0.5f, 0.5f, 0.1f), // 임계값 0.3 미만
            KeypointType.LEFT_ANKLE to Triple(0.5f, 0.8f, 0.9f),
        )

        assertNull(JointAngles.knees(pose, frameAspectRatio = 1f).left)
    }

    @Test
    fun `한쪽만 신뢰 가능하면 그 값이 대표 각도가 된다`() {
        val pose = poseOf(
            KeypointType.LEFT_HIP to Triple(0.4f, 0.2f, 0.9f),
            KeypointType.LEFT_KNEE to Triple(0.4f, 0.5f, 0.9f),
            KeypointType.LEFT_ANKLE to Triple(0.4f, 0.8f, 0.9f),
        )

        val angles = JointAngles.knees(pose, frameAspectRatio = 1f)
        assertNotNull(angles.left)
        assertNull(angles.right)
        assertEquals(angles.left, angles.representative)
        assertNull("한쪽만 있으면 비대칭을 계산할 수 없다", angles.asymmetry)
    }

    @Test
    fun `양쪽 모두 신뢰 가능하면 평균이 대표 각도가 된다`() {
        val pose = poseOf(
            KeypointType.LEFT_HIP to Triple(0.4f, 0.2f, 0.9f),
            KeypointType.LEFT_KNEE to Triple(0.4f, 0.5f, 0.9f),
            KeypointType.LEFT_ANKLE to Triple(0.4f, 0.8f, 0.9f),
            KeypointType.RIGHT_HIP to Triple(0.6f, 0.2f, 0.9f),
            KeypointType.RIGHT_KNEE to Triple(0.6f, 0.5f, 0.9f),
            KeypointType.RIGHT_ANKLE to Triple(0.9f, 0.5f, 0.9f),
        )

        val a = JointAngles.knees(pose, frameAspectRatio = 1f)
        assertEquals((a.left!! + a.right!!) / 2f, a.representative!!, 0.01f)
        assertEquals(kotlin.math.abs(a.left!! - a.right!!), a.asymmetry!!, 0.01f)
    }

    /** 모든 키포인트가 무효인 프레임에서도 예외 없이 null을 돌려줘야 한다. */
    @Test
    fun `빈 포즈는 각도가 없다`() {
        val angles = JointAngles.knees(poseOf(), frameAspectRatio = 1f)

        assertNull(angles.left)
        assertNull(angles.right)
        assertNull(angles.representative)
    }
}
