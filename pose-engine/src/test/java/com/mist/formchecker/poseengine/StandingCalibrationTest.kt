package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캘리브레이션은 **모든 거리 특징의 분모**다. 여기가 틀리면 이후 모든 값이 조용히 틀리고,
 * 촬영을 다시 해야 한다. 손으로 계산 가능한 좌표로 값을 고정한다.
 */
class StandingCalibrationTest {

    private val aspect = 0.75f

    /**
     * 곧게 선 사람. 종횡비 0.75가 x에만 곱해지는 것을 확인하기 위해 좌우 좌표를
     * 대칭으로 두고 세로 거리는 정수 비율로 만들었다.
     *
     * ```
     * 어깨  (0.40, 0.30) / (0.60, 0.30)   → 등방 x 0.30 / 0.45, 폭 0.15
     * 힙    (0.44, 0.50) / (0.56, 0.50)   → 등방 x 0.33 / 0.42, 폭 0.09
     * 무릎  (0.44, 0.70) / (0.56, 0.70)   → 대퇴 0.20
     * 발목  (0.44, 0.90) / (0.56, 0.90)   → 정강이 0.20, 다리 0.40
     * 몸통  (0.375,0.30) → (0.375,0.50)   → 0.20
     * ```
     */
    private fun standingPose(confidence: Float = 0.9f): Pose {
        val points = mutableMapOf<KeypointType, Triple<Float, Float, Float>>()
        fun put(type: KeypointType, x: Float, y: Float) {
            points[type] = Triple(x, y, confidence)
        }
        put(KeypointType.LEFT_SHOULDER, 0.40f, 0.30f)
        put(KeypointType.RIGHT_SHOULDER, 0.60f, 0.30f)
        put(KeypointType.LEFT_HIP, 0.44f, 0.50f)
        put(KeypointType.RIGHT_HIP, 0.56f, 0.50f)
        put(KeypointType.LEFT_KNEE, 0.44f, 0.70f)
        put(KeypointType.RIGHT_KNEE, 0.56f, 0.70f)
        put(KeypointType.LEFT_ANKLE, 0.44f, 0.90f)
        put(KeypointType.RIGHT_ANKLE, 0.56f, 0.90f)
        put(KeypointType.NECK, 0.50f, 0.32f)
        // 발: 뒤꿈치가 발목 아래, 발끝이 앞으로 0.08 (등방 0.06)
        put(KeypointType.LEFT_HEEL, 0.44f, 0.94f)
        put(KeypointType.RIGHT_HEEL, 0.56f, 0.94f)
        put(KeypointType.LEFT_BIG_TOE, 0.52f, 0.94f)
        put(KeypointType.RIGHT_BIG_TOE, 0.64f, 0.94f)
        put(KeypointType.LEFT_SMALL_TOE, 0.52f, 0.94f)
        put(KeypointType.RIGHT_SMALL_TOE, 0.64f, 0.94f)

        val keypoints = KeypointType.entries.map { type ->
            val point = points[type]
            if (point == null) {
                Keypoint(type, 0f, 0f, 0f)
            } else {
                Keypoint(type, point.first, point.second, point.third)
            }
        }
        return Pose(keypoints, 0L)
    }

    private fun frames(count: Int, confidence: Float = 0.9f) =
        List(count) { standingPose(confidence) to aspect }

    @Test
    fun `체절 길이를 손계산값과 같게 잰다`() {
        val calibration = StandingCalibration.from(frames(30))
        assertNotNull(calibration)

        assertEquals("대퇴", 0.20f, calibration!!.femurLength, 0.001f)
        assertEquals("정강이", 0.20f, calibration.tibiaLength, 0.001f)
        assertEquals("다리 전체", 0.40f, calibration.legLength, 0.001f)
        assertEquals("몸통", 0.20f, calibration.trunkLength, 0.001f)
    }

    /**
     * 폭은 x 방향이라 종횡비가 곱해진다. 이 보정을 빼먹으면 골반 너비 분모가 33% 커지고
     * 스탠스 너비 비율이 그만큼 작아진다 — 값이 정상 범위 안에 있어 보여 조용히 틀린다.
     */
    @Test
    fun `가로 길이에 종횡비가 적용된다`() {
        val calibration = StandingCalibration.from(frames(30))!!

        // 골반 좌우 간격 0.12 × 0.75 = 0.09
        assertEquals("골반 너비", 0.09f, calibration.hipWidth, 0.001f)
        // 어깨 0.20 × 0.75 = 0.15
        assertEquals("어깨 너비", 0.15f, calibration.shoulderWidth, 0.001f)
    }

    @Test
    fun `종횡비를 바꾸면 가로 길이만 변한다`() {
        val a = StandingCalibration.from(List(30) { standingPose() to 0.75f })!!
        val b = StandingCalibration.from(List(30) { standingPose() to 1.50f })!!

        assertEquals(a.hipWidth * 2f, b.hipWidth, 0.001f)
        assertEquals("세로 길이는 영향받지 않는다", a.femurLength, b.femurLength, 0.001f)
    }

    /**
     * 대퇴/정강이 비율은 같은 깊이에서의 무릎 각도를 바꾸는 체형 변수다. 다리 길이 합만
     * 남기면 "무릎각이 큰 것이 얕게 앉은 것인지 대퇴가 긴 것인지" 구분할 수 없다.
     */
    @Test
    fun `대퇴 정강이 비율을 따로 낸다`() {
        val calibration = StandingCalibration.from(frames(30))!!
        assertEquals(1.0f, calibration.femurTibiaRatio, 0.001f)
    }

    @Test
    fun `곧게 선 자세의 무릎 굴곡은 0에 가깝다`() {
        val calibration = StandingCalibration.from(frames(30))!!
        assertTrue(
            "굴곡각 규약: 곧게 서면 0 (실제 ${calibration.standingKneeFlexion})",
            calibration.standingKneeFlexion < 1f,
        )
    }

    // ── 표본 부족·통과 조건 ──────────────────────────────────

    @Test
    fun `프레임이 부족하면 캘리브레이션하지 않는다`() {
        assertNull(StandingCalibration.from(frames(5)))
    }

    /**
     * 정적 자세라 프레임을 평균할 수 있다는 것이 이 설계의 핵심이다. confidence가 낮은
     * 프레임이 섞이면 기준선이 흔들리므로 아예 제외한다.
     */
    @Test
    fun `confidence가 낮은 프레임은 제외한다`() {
        // 신뢰 가능한 프레임이 하한(20) 이상이어야 캘리브레이션이 성립한다.
        val mixed = frames(25, 0.9f) + frames(15, 0.3f)
        val calibration = StandingCalibration.from(mixed)

        assertNotNull(calibration)
        assertEquals("신뢰 가능한 프레임만 평균한다", 25, calibration!!.framesUsed)
    }

    @Test
    fun `필수 키포인트가 없으면 캘리브레이션하지 않는다`() {
        val noLegs = List(30) {
            val base = standingPose()
            val stripped = base.keypoints.mapIndexed { index, keypoint ->
                if (index == KeypointType.LEFT_KNEE.index ||
                    index == KeypointType.RIGHT_KNEE.index
                ) {
                    keypoint.copy(confidence = 0f)
                } else {
                    keypoint
                }
            }
            Pose(stripped, 0L) to aspect
        }
        assertNull(StandingCalibration.from(noLegs))
    }

    @Test
    fun `정상 기립 자세는 통과한다`() {
        val result = StandingCalibration.from(frames(30))!!.validate()
        assertTrue("문제: ${result.problems}", result.passed)
    }

    /** 사람이 너무 작게 찍히면 모든 길이가 작아져 비율 특징의 분해능이 떨어진다. */
    @Test
    fun `사람이 너무 작게 찍히면 차단한다`() {
        // 다리 길이를 0.08로 줄인다 (하한 0.15 미만)
        val small = List(30) {
            val base = standingPose()
            val shrunk = base.keypoints.map { it.copy(y = it.y * 0.2f) }
            Pose(shrunk, 0L) to aspect
        }
        val result = StandingCalibration.from(small)!!.validate()

        assertFalse(result.passed)
        assertTrue(result.problems.contains(StandingCalibration.Problem.SUBJECT_TOO_SMALL))
    }

    /**
     * 발이 안 보이는 것은 차단하지 않는다. 측면에서는 먼 쪽 발이 가려지는 것이 정상이고,
     * 여기서 막으면 측면 수집이 아예 안 된다. 발 관련 특징만 못 쓰게 된다.
     */
    @Test
    fun `발이 안 보여도 캘리브레이션은 통과한다`() {
        val noFeet = List(30) {
            val base = standingPose()
            val stripped = base.keypoints.mapIndexed { index, keypoint ->
                if (index >= KeypointType.LEFT_BIG_TOE.index) {
                    keypoint.copy(confidence = 0f)
                } else {
                    keypoint
                }
            }
            Pose(stripped, 0L) to aspect
        }
        val calibration = StandingCalibration.from(noFeet)!!
        val result = calibration.validate()

        assertTrue("발 부재는 차단 사유가 아니다", result.passed)
        assertTrue(result.problems.contains(StandingCalibration.Problem.FOOT_NOT_VISIBLE))
        assertEquals(0f, calibration.footLength, 0.0001f)
    }

    /** 기준선이 흔들리면 이후 모든 특징이 흔들린다. */
    @Test
    fun `움직임이 감지되면 차단한다`() {
        val moving = List(30) { index ->
            val base = standingPose()
            val shifted = base.keypoints.map {
                it.copy(y = it.y + index * 0.004f)
            }
            Pose(shifted, 0L) to aspect
        }
        // y 이동만으로는 길이가 변하지 않으므로 무릎만 움직여 길이를 흔든다.
        val jittery = List(30) { index ->
            val base = standingPose()
            val shifted = base.keypoints.mapIndexed { i, keypoint ->
                if (i == KeypointType.LEFT_KNEE.index || i == KeypointType.RIGHT_KNEE.index) {
                    keypoint.copy(y = keypoint.y + (index % 2) * 0.05f)
                } else {
                    keypoint
                }
            }
            Pose(shifted, 0L) to aspect
        }

        assertTrue(
            "평행 이동은 길이를 바꾸지 않아 통과해야 한다",
            StandingCalibration.from(moving)!!.validate().passed,
        )
        val result = StandingCalibration.from(jittery)!!.validate()
        assertFalse(result.passed)
        assertTrue(result.problems.contains(StandingCalibration.Problem.UNSTABLE))
    }

    /**
     * 활성측은 세션 내내 고정해야 하므로(문서 §4.3) 정적인 캘리브레이션 창에서 정한다.
     * 가까운 쪽이 신뢰도가 높다 — 먼 쪽은 몸에 가려져 추정값이 된다.
     */
    private fun sidedPose(leftConfidence: Float, rightConfidence: Float): Pose {
        val base = standingPose()
        val keypoints = base.keypoints.map { point ->
            val side = when (point.type) {
                KeypointType.LEFT_HIP, KeypointType.LEFT_KNEE, KeypointType.LEFT_ANKLE ->
                    leftConfidence
                KeypointType.RIGHT_HIP, KeypointType.RIGHT_KNEE, KeypointType.RIGHT_ANKLE ->
                    rightConfidence
                else -> point.confidence
            }
            Keypoint(point.type, point.x, point.y, side)
        }
        return Pose(keypoints, 0L)
    }

    @Test
    fun `신뢰도가 높은 쪽을 가까운 다리로 본다`() {
        val leftNear = List(30) { sidedPose(leftConfidence = 0.9f, rightConfidence = 0.5f) to aspect }
        assertEquals(Side.LEFT, StandingCalibration.nearerSide(leftNear))

        val rightNear = List(30) { sidedPose(leftConfidence = 0.5f, rightConfidence = 0.9f) to aspect }
        assertEquals(Side.RIGHT, StandingCalibration.nearerSide(rightNear))
    }

    /**
     * 한 프레임이 튀어도 판단이 뒤집히지 않아야 한다. 정적 구간을 평균하는 이유가 이것이다 —
     * 동작 중에 정하면 그 순간의 가림 상태가 세션 전체의 기준을 결정한다.
     */
    @Test
    fun `한 프레임의 튐으로 활성측이 뒤집히지 않는다`() {
        val frames = List(29) { sidedPose(leftConfidence = 0.8f, rightConfidence = 0.6f) to aspect } +
            listOf(sidedPose(leftConfidence = 0.1f, rightConfidence = 0.99f) to aspect)
        assertEquals(Side.LEFT, StandingCalibration.nearerSide(frames))
    }

    @Test
    fun `프레임이 없으면 활성측을 정하지 않는다`() {
        assertNull(StandingCalibration.nearerSide(emptyList()))
    }

    /** 좌우가 같으면 한쪽으로 고정한다. 프레임마다 갈아타는 것보다 낫다. */
    @Test
    fun `좌우 신뢰도가 같으면 왼쪽으로 고정한다`() {
        val frames = List(30) { sidedPose(leftConfidence = 0.7f, rightConfidence = 0.7f) to aspect }
        assertEquals(Side.LEFT, StandingCalibration.nearerSide(frames))
    }
}
