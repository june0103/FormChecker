package com.mist.formchecker.poseengine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "지금 rep 안인가" — 자세 판정을 할 수 있는 구간인가.
 *
 * ## 이 규칙은 실기 신고에서 생겼다
 * 화면 쪽에 이 게이트가 없어서 **가만히 서 있어도 "양발로 고르게 일어서세요"가 떴다**
 * (2026-08-24). 서 있는 사람에게 일어서라고 말하는 문구다.
 *
 * 근거는 임계값의 출처다 — `hipShiftLimit`은 정상 의도 9명 794프레임의 **최저점** 분포에서,
 * `kneeToeToleranceRatio`는 11명 253 rep 최저점의 사람내 MAD에서 나왔다. 선 자세 프레임에
 * 그 값을 대면 도출 범위 밖에서 판정하는 것이고, 무릎이 깊이 게이트 없이 선 자세에서
 * 초과율 100%였던 것과 같은 오류가 된다.
 *
 * **규칙을 되돌리려면 이 테스트가 함께 깨져야 한다.**
 */
class RepStateInRepTest {

    /** 움직이는 구간 세 개가 rep 안이다. */
    @Test
    fun `하강_최저점_상승은 rep 안이다`() {
        assertTrue(RepState.DESCENDING.isInRep)
        assertTrue(RepState.BOTTOM.isInRep)
        assertTrue(RepState.ASCENDING.isInRep)
    }

    /**
     * **이 두 개가 신고의 원인이다.** 선 자세에서 판정하면 안 된다.
     */
    @Test
    fun `선 자세는 rep 밖이다`() {
        assertFalse("서 있을 때 자세 경고가 뜨면 안 된다", RepState.STANDING.isInRep)
    }

    /** 사람을 못 찾은 프레임은 판정할 근거 자체가 없다. */
    @Test
    fun `미인식은 rep 밖이다`() {
        assertFalse(RepState.IDLE.isInRep)
    }

    /**
     * 새 상태를 추가하면 `when`이 컴파일 시점에 막아 주지만, **분류를 빠뜨리는 것**은 막지
     * 못한다. 전체 개수를 붙들어 두면 새 상태가 조용히 한쪽으로 들어가는 것을 알아챌 수 있다.
     */
    @Test
    fun `모든 상태가 분류돼 있다`() {
        assertTrue(RepState.entries.isNotEmpty())
        val inRep = RepState.entries.count { it.isInRep }
        val outside = RepState.entries.count { !it.isInRep }
        org.junit.Assert.assertEquals("rep 안 상태가 3개여야 한다", 3, inRep)
        org.junit.Assert.assertEquals("rep 밖 상태가 2개여야 한다", 2, outside)
    }
}
