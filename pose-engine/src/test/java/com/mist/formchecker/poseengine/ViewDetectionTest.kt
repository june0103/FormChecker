package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 촬영 방향 판별.
 *
 * 사용자가 각도를 고르지 않게 되면서 **이 판별이 어느 판정을 켤지 정한다.** 틀리면 옆으로
 * 선 사람에게 무릎 정렬을 말하거나 정면인 사람의 깊이를 잰다.
 *
 * 실측(정면 12 / 측면 3세션) 세션별 READY 중앙값이 정면 0.675~0.731 / 측면 0.058~0.105로
 * 0.15와 0.55 사이가 비어 있다. 여기 있는 좌표는 그 두 무리를 대표한다.
 */
class ViewDetectionTest {

    private val aspect = 0.75f

    /**
     * @param shoulderHalf 어깨 반폭(원좌표). 등방 폭은 `× 2 × 0.75`.
     *   몸통 길이는 0.20으로 고정이므로 `어깨폭/몸통`은 `shoulderHalf × 7.5`다.
     */
    private fun pose(shoulderHalf: Float, confidence: Float = 0.9f): Pose {
        val points = mutableMapOf<KeypointType, Triple<Float, Float, Float>>()
        fun put(type: KeypointType, x: Float, y: Float) {
            points[type] = Triple(x, y, confidence)
        }
        put(KeypointType.LEFT_SHOULDER, 0.50f - shoulderHalf, 0.30f)
        put(KeypointType.RIGHT_SHOULDER, 0.50f + shoulderHalf, 0.30f)
        put(KeypointType.LEFT_HIP, 0.49f, 0.50f)
        put(KeypointType.RIGHT_HIP, 0.51f, 0.50f)
        val keypoints = KeypointType.entries.map { type ->
            points[type]?.let { Keypoint(type, it.first, it.second, it.third) }
                ?: Keypoint(type, 0f, 0f, 0f)
        }
        return Pose(keypoints, 0L)
    }

    /** 실측 정면 중앙값(≈0.70)에 해당하는 어깨 반폭. */
    private val frontHalf = 0.093f

    /** 실측 측면 중앙값(≈0.09)에 해당하는 어깨 반폭. */
    private val sideHalf = 0.012f

    /** 두 무리 사이. 45도로 서 있는 경우다. */
    private val obliqueHalf = 0.055f

    @Test
    fun `실측 정면 중앙값은 정면으로 읽힌다`() {
        assertEquals(
            CaptureQuality.EstimatedView.FRONT,
            CaptureQuality.estimateView(pose(frontHalf), aspect),
        )
    }

    @Test
    fun `실측 측면 중앙값은 측면으로 읽힌다`() {
        assertEquals(
            CaptureQuality.EstimatedView.SIDE,
            CaptureQuality.estimateView(pose(sideHalf), aspect),
        )
    }

    /** 45도는 정면 기준도 측면 기준도 대응하지 않는다. 판정하지 않아야 한다. */
    @Test
    fun `두 무리 사이는 애매로 읽힌다`() {
        assertEquals(
            CaptureQuality.EstimatedView.AMBIGUOUS,
            CaptureQuality.estimateView(pose(obliqueHalf), aspect),
        )
    }

    @Test
    fun `어깨가 안 보이면 추정하지 않는다`() {
        assertNull(CaptureQuality.estimateView(pose(frontHalf, confidence = 0.1f), aspect))
    }

    // ── 다수결 ───────────────────────────────────────────────

    private fun frames(vararg halves: Float) = halves.map { pose(it) to aspect }

    /**
     * 프레임 단위 오분류가 다수결에서 흡수돼야 한다. 실측에서 어깨 키포인트가 무너진
     * 정면 세션이 8.7%를 측면으로 읽었고, 그래도 세션 중앙값은 정면이었다.
     */
    @Test
    fun `소수의 오분류 프레임은 다수결이 흡수한다`() {
        val mixed = frames(
            frontHalf, frontHalf, frontHalf, frontHalf,
            sideHalf, // 어깨가 무너진 프레임
            frontHalf, frontHalf, frontHalf,
        )
        assertEquals(CaptureQuality.EstimatedView.FRONT, CaptureQuality.estimateView(mixed))
    }

    @Test
    fun `애매한 프레임이 섞여도 다수가 이긴다`() {
        val mixed = frames(sideHalf, sideHalf, obliqueHalf, sideHalf, obliqueHalf)
        assertEquals(CaptureQuality.EstimatedView.SIDE, CaptureQuality.estimateView(mixed))
    }

    /** 정말 비스듬하게 서 있으면 다수결도 애매다 — 그때는 진행하면 안 된다. */
    @Test
    fun `대부분이 애매하면 애매가 나온다`() {
        val mixed = frames(obliqueHalf, obliqueHalf, obliqueHalf, frontHalf)
        assertEquals(CaptureQuality.EstimatedView.AMBIGUOUS, CaptureQuality.estimateView(mixed))
    }

    @Test
    fun `프레임이 없으면 null이다`() {
        assertNull(CaptureQuality.estimateView(emptyList()))
    }

    @Test
    fun `추정 불가 프레임만 있으면 null이다`() {
        val invisible = List(5) { pose(frontHalf, confidence = 0.1f) to aspect }
        assertNull(CaptureQuality.estimateView(invisible))
    }

    // ── 경계값 ───────────────────────────────────────────────

    /**
     * 측면 경계를 0.25에서 0.30으로 올렸다. 두 값이 두 파일에 갈라져 있던 것을 합친
     * 결과이므로, 되돌리면 이 테스트가 깨져야 한다.
     */
    @Test
    fun `측면 경계는 0_30이다`() {
        assertEquals(0.30f, AngleDetectionThresholds().sideRatio, 1e-6f)
        // 어깨폭/몸통 = 0.29 → 측면. 0.31 → 애매.
        assertEquals(
            CaptureQuality.EstimatedView.SIDE,
            CaptureQuality.estimateView(pose(0.29f / 7.5f), aspect),
        )
        assertEquals(
            CaptureQuality.EstimatedView.AMBIGUOUS,
            CaptureQuality.estimateView(pose(0.31f / 7.5f), aspect),
        )
    }

    @Test
    fun `경계는 설정으로 바뀐다`() {
        val loose = AngleDetectionThresholds(sideRatio = 0.5f)
        assertEquals(
            CaptureQuality.EstimatedView.SIDE,
            CaptureQuality.estimateView(pose(obliqueHalf), aspect, loose),
        )
    }

    /**
     * 종횡비를 넘기지 않으면 어깨폭(가로)만 화면 비율만큼 늘어나 비율이 어긋난다.
     * 임계값은 등방 좌표로 확정했다.
     */
    @Test
    fun `종횡비가 결과를 바꾼다`() {
        val borderline = pose(0.29f / 7.5f)
        assertEquals(
            CaptureQuality.EstimatedView.SIDE,
            CaptureQuality.estimateView(borderline, aspect),
        )
        // 보정을 빼면 어깨폭이 1/0.75 배로 읽혀 경계를 넘는다.
        assertEquals(
            CaptureQuality.EstimatedView.AMBIGUOUS,
            CaptureQuality.estimateView(borderline, 1f),
        )
    }
}
