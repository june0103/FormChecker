package com.mist.formchecker.poseengine

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleEmaTest {

    /** 30fps 기준 프레임 간격. */
    private val frameMs = 33L

    @Test
    fun `첫 유효 입력은 그대로 통과한다`() {
        val ema = AngleEma(timeConstantMs = 120.0)

        assertEquals(170f, ema.update(170f, 0L)!!, 0.001f)
    }

    @Test
    fun `유효 입력이 없으면 null을 유지한다`() {
        val ema = AngleEma(timeConstantMs = 120.0)

        assertNull(ema.update(null, 0L))
        assertNull(ema.current)
    }

    /**
     * 저신뢰 프레임에서 값을 갱신하면 스무딩이 무너진다. 가림 한두 프레임으로 rep이
     * 끊기지 않으려면 직전 값을 유지해야 한다.
     */
    @Test
    fun `저신뢰 프레임에서는 직전 값을 유지한다`() {
        val ema = AngleEma(timeConstantMs = 120.0)
        ema.update(100f, 0L)

        val held = ema.update(null, frameMs)

        assertEquals(100f, held!!, 0.001f)
        assertEquals(100f, ema.current!!, 0.001f)
    }

    @Test
    fun `계단 입력을 시간에 걸쳐 따라간다`() {
        val ema = AngleEma(timeConstantMs = 120.0)
        ema.update(0f, 0L)

        // 시간상수 1배 경과 시 약 63%를 따라잡아야 한다.
        val after1Tau = ema.update(100f, 120L)!!
        assertEquals(63f, after1Tau, 4f)

        // 3배 경과하면 약 95%.
        val after3Tau = ema.update(100f, 480L)!!
        assertTrue("실제: $after3Tau", after3Tau > 90f)
    }

    /**
     * 프레임 간격이 두 배로 늘어나면(fps 절반) 같은 시간 동안 따라잡는 정도가 같아야 한다.
     * alpha를 상수로 두면 이 성질이 깨져 fps에 따라 스무딩 강도가 달라진다.
     */
    @Test
    fun `분석 fps가 달라도 같은 시간에 같은 만큼 따라간다`() {
        fun trackAfter(stepMs: Long): Float {
            val ema = AngleEma(timeConstantMs = 120.0)
            ema.update(0f, 0L)
            var t = 0L
            while (t < 240L) {
                t += stepMs
                ema.update(100f, t)
            }
            return ema.current!!
        }

        val at30fps = trackAfter(33L)   // 약 7프레임
        val at15fps = trackAfter(66L)   // 약 4프레임

        assertEquals("fps가 달라도 240ms 후 값이 비슷해야 한다", at30fps, at15fps, 3f)
    }

    @Test
    fun `노이즈를 완화한다`() {
        val ema = AngleEma(timeConstantMs = 120.0)
        var t = 0L
        var maxDeviation = 0f

        // 100도 신호에 ±15도 노이즈를 번갈아 주입
        repeat(40) { i ->
            val noisy = 100f + if (i % 2 == 0) 15f else -15f
            val smoothed = ema.update(noisy, t)!!
            if (i > 5) maxDeviation = maxOf(maxDeviation, abs(smoothed - 100f))
            t += frameMs
        }

        assertTrue("노이즈가 절반 이하로 줄어야 한다 (실제 편차 $maxDeviation)", maxDeviation < 7.5f)
    }

    @Test
    fun `reset하면 초기 상태로 돌아간다`() {
        val ema = AngleEma(timeConstantMs = 120.0)
        ema.update(150f, 0L)

        ema.reset()

        assertNull(ema.current)
        assertEquals(80f, ema.update(80f, frameMs)!!, 0.001f)
    }
}

class MedianWindowTest {

    @Test
    fun `창이 비어 있으면 null이다`() {
        assertNull(MedianWindow(5).median())
    }

    @Test
    fun `홀수 창은 중앙값을 돌려준다`() {
        val w = MedianWindow(5)
        listOf(10f, 50f, 20f, 40f, 30f).forEach { w.push(it) }

        assertEquals(30f, w.median()!!, 0.001f)
    }

    @Test
    fun `창이 가득 차기 전에도 값이 나온다`() {
        val w = MedianWindow(5)
        w.push(10f)
        w.push(20f)

        assertEquals(2, w.count)
        assertEquals(15f, w.median()!!, 0.001f)
    }

    @Test
    fun `창 크기를 넘으면 오래된 값이 밀려난다`() {
        val w = MedianWindow(3)
        listOf(100f, 100f, 100f, 1f, 1f, 1f).forEach { w.push(it) }

        assertEquals(3, w.count)
        assertEquals(1f, w.median()!!, 0.001f)
    }

    /** 단일 스파이크는 중앙값에 영향을 주지 않아야 한다. */
    @Test
    fun `한 프레임 튐을 제거한다`() {
        val w = MedianWindow(5)
        listOf(100f, 101f, 999f, 99f, 100f).forEach { w.push(it) }

        assertEquals(100f, w.median()!!, 1.5f)
    }

    @Test
    fun `null은 창에 들어가지 않는다`() {
        val w = MedianWindow(5)
        w.push(100f)
        w.push(null)
        w.push(102f)

        assertEquals("null은 개수에 반영되지 않는다", 2, w.count)
        assertEquals(101f, w.median()!!, 0.001f)
    }

    @Test
    fun `시간 폭에서 창 크기를 계산한다`() {
        // 133ms @ 30fps ≈ 4프레임 → 홀수로 보정해 5
        assertEquals(5, MedianWindow.sizeFor(targetSpanMs = 133.0, fps = 30.0))
        // 133ms @ 15fps ≈ 2프레임 → 홀수로 보정해 3
        assertEquals(3, MedianWindow.sizeFor(targetSpanMs = 133.0, fps = 15.0))
        // 비정상 입력은 1로 방어
        assertEquals(1, MedianWindow.sizeFor(targetSpanMs = 133.0, fps = 0.0))
        assertEquals(1, MedianWindow.sizeFor(targetSpanMs = 0.0, fps = 30.0))
    }
}

/**
 * 두 스무딩을 나눠 쓰는 이유를 고정하는 테스트.
 *
 * 이 성질이 깨지면 최저점 탐색에 EMA를 써도 된다는 잘못된 판단으로 이어질 수 있다.
 */
class SmoothingPhaseLagTest {

    /**
     * 스쿼트 한 번을 모사한 사인파에서 최저점 프레임을 찾는다.
     *
     * EMA는 위상 지연이 있어 최저점이 뒤로 밀리고, median filter는 밀리지 않아야 한다.
     */
    @Test
    fun `EMA는 최저점을 뒤로 밀지만 median은 밀지 않는다`() {
        val frames = 90                     // 30fps 기준 3초
        val trueBottom = frames / 2
        // 170도에서 시작해 최저점에서 70도까지 내려가는 반주기 사인
        val signal = (0 until frames).map { i ->
            val phase = Math.PI * i / (frames - 1)
            (170.0 - 100.0 * sin(phase)).toFloat()
        }

        val ema = AngleEma(timeConstantMs = 120.0)
        val median = MedianWindow(5)
        var emaBottomIdx = 0
        var emaBottomVal = Float.MAX_VALUE
        var medBottomIdx = 0
        var medBottomVal = Float.MAX_VALUE

        signal.forEachIndexed { i, v ->
            val e = ema.update(v, i * 33L)!!
            if (e < emaBottomVal) { emaBottomVal = e; emaBottomIdx = i }
            val m = median.push(v)!!
            if (m < medBottomVal) { medBottomVal = m; medBottomIdx = i }
        }

        val emaLag = emaBottomIdx - trueBottom
        val medLag = medBottomIdx - trueBottom

        assertTrue("EMA 최저점이 뒤로 밀려야 한다 (지연 $emaLag 프레임)", emaLag > 0)
        assertTrue(
            "median 최저점 지연이 EMA보다 작아야 한다 (median $medLag, EMA $emaLag)",
            medLag < emaLag,
        )
    }

    /**
     * EMA는 최저 각도 자체도 완화한다. 깊이 판정에 EMA 값을 쓰면 실제보다 얕게 나온다.
     */
    @Test
    fun `EMA는 최저 각도를 실제보다 얕게 만든다`() {
        val frames = 60
        val signal = (0 until frames).map { i ->
            val phase = Math.PI * i / (frames - 1)
            (170.0 - 100.0 * sin(phase)).toFloat()
        }
        val trueMin = signal.min()

        val ema = AngleEma(timeConstantMs = 120.0)
        var emaMin = Float.MAX_VALUE
        signal.forEachIndexed { i, v -> emaMin = minOf(emaMin, ema.update(v, i * 33L)!!) }

        assertNotNull(trueMin)
        assertTrue(
            "EMA 최저값($emaMin)이 실제 최저값($trueMin)보다 커야(얕아야) 한다",
            emaMin > trueMin,
        )
    }
}
