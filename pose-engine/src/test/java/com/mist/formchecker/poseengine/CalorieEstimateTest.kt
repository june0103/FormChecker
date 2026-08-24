package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 소모 열량 추정.
 *
 * 근거가 문헌이므로 **문헌과 맞는지**를 붙든다 — 상수를 손대면 여기가 깨져야 한다.
 * 출처는 [CalorieEstimate] 주석에 있다.
 */
class CalorieEstimateTest {

    private val height = 170f
    private val weight = 70f

    // ── 문헌과의 일치 ────────────────────────────────────────

    /**
     * Mifflin-St Jeor 식을 손으로 계산한 값과 같아야 한다.
     * 남 70kg · 170cm · 25세 → `10(70) + 6.25(170) − 5(25) + 5 = 1642.5`
     */
    @Test
    fun `기초대사량이 Mifflin_St Jeor 식과 같다`() {
        assertEquals(
            1642.5f,
            CalorieEstimate.restingKcalPerDay(170f, 70f, 25, Sex.MALE),
            0.01f,
        )
        // 여성은 상수항만 다르다: +5 대신 −161 → 166 차이.
        assertEquals(
            1642.5f - 166f,
            CalorieEstimate.restingKcalPerDay(170f, 70f, 25, Sex.FEMALE),
            0.01f,
        )
    }

    /**
     * **이 테스트가 이 기능의 근거다.**
     *
     * Compendium MET(5.0)과 Mifflin-St Jeor로 계산한 1회당 열량이, 독립적으로 측정된
     * Nakagata et al.(2022)의 **0.50 ± 0.14 kcal (95% CI 0.42~0.58)** 안에 들어야 한다.
     * 두 문헌이 서로를 검증하는 자리이고, 상수를 바꾸면 그 일치가 깨진다.
     */
    @Test
    fun `문헌 실측 신뢰구간 안에 들어온다`() {
        // 실측 표본과 같은 조건: 20대 남성.
        val perRep = CalorieEstimate.perRep(height, weight, age = 25, sex = Sex.MALE)!!
        assertTrue(
            "1회당 $perRep kcal — Nakagata 95% CI(0.42~0.58) 밖이다",
            perRep in 0.42f..0.58f,
        )
    }

    @Test
    fun `스쿼트 MET는 Compendium 코드 02052 값이다`() {
        assertEquals(5.0f, CalorieEstimate.SQUAT_MET, 1e-6f)
    }

    // ── 횟수 기반 ────────────────────────────────────────────

    /** 총 열량은 1회당 × 횟수다. 깊이는 보지 않는다. */
    @Test
    fun `총 열량은 횟수에 비례한다`() {
        val ten = CalorieEstimate.forSession(10, height, weight)!!
        val twenty = CalorieEstimate.forSession(20, height, weight)!!
        assertEquals(2f, twenty.kcal / ten.kcal, 1e-4f)
        assertEquals(20, twenty.reps)
    }

    @Test
    fun `횟수가 0이면 null이다`() {
        assertNull(CalorieEstimate.forSession(0, height, weight))
    }

    /**
     * 1회당 열량은 **템포에 좌우되지 않는다.** Nakagata et al.가 회귀 기울기로 정의했고,
     * 그래서 이 함수에 시간이 들어가지 않는다 — 시그니처가 그 사실을 붙든다.
     */
    @Test
    fun `1회당 열량에 시간이 들어가지 않는다`() {
        val a = CalorieEstimate.perRep(height, weight)!!
        val b = CalorieEstimate.perRep(height, weight)!!
        assertEquals(a, b, 1e-6f)
    }

    // ── 키·몸무게 반영 ───────────────────────────────────────

    /** 몸무게가 늘면 늘어난다. Mifflin-St Jeor에서 체중 계수가 10 kcal/kg다. */
    @Test
    fun `몸무게가 많으면 더 많이 나온다`() {
        val light = CalorieEstimate.perRep(height, 50f)!!
        val heavy = CalorieEstimate.perRep(height, 90f)!!
        assertTrue(heavy > light)
    }

    /** **키가 반영된다.** MET 표준식(체중만 봄)을 쓰지 않은 이유가 이것이다. */
    @Test
    fun `키가 크면 더 많이 나온다`() {
        val short = CalorieEstimate.perRep(150f, weight)!!
        val tall = CalorieEstimate.perRep(190f, weight)!!
        assertTrue("키가 결과를 바꾸지 않는다", tall > short)
    }

    @Test
    fun `나이가 많으면 조금 줄어든다`() {
        val young = CalorieEstimate.perRep(height, weight, age = 20)!!
        val old = CalorieEstimate.perRep(height, weight, age = 70)!!
        assertTrue(young > old)
    }

    @Test
    fun `여성이 남성보다 낮게 나온다`() {
        val male = CalorieEstimate.perRep(height, weight, sex = Sex.MALE)!!
        val female = CalorieEstimate.perRep(height, weight, sex = Sex.FEMALE)!!
        assertTrue(male > female)
    }

    // ── 가정을 밝힌다 ───────────────────────────────────────

    /**
     * 나이·성별을 안 넣으면 기본값을 쓴다. **그 사실이 결과에 실려야** 화면이 밝힐 수 있다 —
     * 숨기면 사용자가 값을 실측으로 읽는다.
     */
    @Test
    fun `가정을 썼는지 결과에 남는다`() {
        val assumed = CalorieEstimate.forSession(10, height, weight)!!
        assertTrue(assumed.assumedAge)
        assertTrue(assumed.assumedSex)
        assertTrue(!assumed.exact)

        val given = CalorieEstimate.forSession(10, height, weight, age = 25, sex = Sex.MALE)!!
        assertTrue(given.exact)
    }

    // ── 모를 때 ──────────────────────────────────────────────

    /** 0이 아니라 null이다. 0은 "안 태웠다"이고 여기서는 "모른다"다. */
    @Test
    fun `키나 몸무게가 없으면 null이다`() {
        assertNull(CalorieEstimate.perRep(0f, weight))
        assertNull(CalorieEstimate.perRep(height, 0f))
    }
}
