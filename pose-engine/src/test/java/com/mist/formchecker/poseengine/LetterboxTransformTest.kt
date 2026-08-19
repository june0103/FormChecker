package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxTransformTest {

    /** 16:9 가로 프레임은 위아래에 패딩이 붙고 좌우는 꽉 찬다. */
    @Test
    fun `가로로 긴 프레임은 상하에 패딩이 생긴다`() {
        val t = LetterboxTransform(1920, 1080, 192)

        assertEquals(192, t.scaledWidth)
        assertEquals(108, t.scaledHeight)
        assertEquals(0, t.padLeft)
        assertEquals(42, t.padTop)
    }

    @Test
    fun `세로로 긴 프레임은 좌우에 패딩이 생긴다`() {
        val t = LetterboxTransform(1080, 1920, 192)

        assertEquals(108, t.scaledWidth)
        assertEquals(192, t.scaledHeight)
        assertEquals(42, t.padLeft)
        assertEquals(0, t.padTop)
    }

    @Test
    fun `정사각 프레임은 패딩이 없다`() {
        val t = LetterboxTransform(720, 720, 192)

        assertEquals(0, t.padLeft)
        assertEquals(0, t.padTop)
        assertEquals(192, t.scaledWidth)
    }

    /**
     * 이 왕복 검증이 letterbox 구현의 핵심이다. inverse가 forward를 정확히 되돌리지
     * 못하면 스켈레톤이 패딩 폭만큼 어긋나고 각도까지 함께 틀어진다.
     */
    @Test
    fun `forward 후 inverse하면 원래 좌표로 돌아온다`() {
        val t = LetterboxTransform(1920, 1080, 192)

        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (y in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val (lx, ly) = t.forward(x, y)
                val (ox, oy) = t.inverse(lx, ly)
                assertEquals("x=$x", x, ox, 0.01f)
                assertEquals("y=$y", y, oy, 0.01f)
            }
        }
    }

    /** 프레임 중심은 letterbox 후에도 정사각형의 중심이어야 한다. */
    @Test
    fun `중심점은 변환 후에도 중심이다`() {
        val t = LetterboxTransform(1920, 1080, 192)
        val (cx, cy) = t.forward(0.5f, 0.5f)

        assertEquals(0.5f, cx, 0.01f)
        assertEquals(0.5f, cy, 0.01f)
    }

    /**
     * 단순 리사이즈였다면 y가 그대로 0.5에 머물지만, letterbox에서는 위쪽 패딩만큼
     * 안쪽으로 밀려야 한다. 이 차이가 없으면 letterbox가 동작하지 않는 것이다.
     */
    @Test
    fun `letterbox는 단순 리사이즈와 다른 좌표를 만든다`() {
        val t = LetterboxTransform(1920, 1080, 192)
        val (_, topY) = t.forward(0.5f, 0f)

        assertTrue("상단이 패딩만큼 안쪽으로 밀려야 한다 (실제: $topY)", topY > 0.2f)
    }

    @Test
    fun `유효하지 않은 크기는 거부한다`() {
        val invalid = listOf(
            { LetterboxTransform(0, 1080, 192) },
            { LetterboxTransform(1920, 0, 192) },
            { LetterboxTransform(1920, 1080, 0) },
        )
        invalid.forEach { create ->
            try {
                create()
                throw AssertionError("예외가 발생해야 합니다")
            } catch (expected: IllegalArgumentException) {
                // 의도된 동작
            }
        }
    }
}
