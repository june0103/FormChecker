package com.mist.formchecker.ui.guide

import com.mist.formchecker.data.GuideKey
import com.mist.formchecker.ui.onboarding.OnboardingPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 문구와 단계 구성이 지켜야 할 규칙.
 *
 * 화면을 띄우지 않고 확인할 수 있는 것만 여기서 붙든다 — **무엇을 강조하는가**(개발용이
 * 섞이지 않았나), **무엇을 말하는가**(하지 않기로 한 말이 들어가지 않았나), 그리고
 * 대상이 없을 때 단계를 건너뛰는가.
 */
class CoachStepsTest {

    private val allSteps = CoachSteps.home + CoachSteps.workout + CoachSteps.summary +
        CoachSteps.history + CoachSteps.settings

    @Test
    fun `모든 단계에 제목과 설명이 있다`() {
        allSteps.forEach { step ->
            assertTrue("제목이 비었다: ${step.anchor}", step.title.isNotBlank())
            assertTrue("설명이 비었다: ${step.anchor}", step.body.isNotBlank())
        }
    }

    /** 같은 화면에서 한 대상을 두 번 설명하면 같은 곳을 두 번 강조하게 된다. */
    @Test
    fun `한 화면 안에서 대상이 겹치지 않는다`() {
        listOf(
            CoachSteps.home,
            CoachSteps.workout,
            CoachSteps.summary,
            CoachSteps.history,
            CoachSteps.settings,
        ).forEach { steps ->
            assertEquals(steps.size, steps.map { it.anchor }.toSet().size)
        }
    }

    /**
     * **개발·검증용을 강조하지 않는다.** 홈 하단의 수집·개발용 버튼은 안내 대상이 아니고,
     * 대상 목록이 홈 화면의 사용자 기능 다섯 개로 닫혀 있어야 한다.
     */
    @Test
    fun `홈 안내는 사용자 기능만 가리킨다`() {
        assertEquals(
            listOf(
                CoachSteps.Home.GOAL,
                CoachSteps.Home.START,
                CoachSteps.Home.WEEK,
                CoachSteps.Home.HISTORY,
                CoachSteps.Home.SETTINGS,
            ),
            CoachSteps.home.map { it.anchor },
        )
    }

    /**
     * 대상이 없는 단계는 빠진다. 결과 화면의 `동작별 기록`처럼 데이터가 있어야 나타나는
     * 항목이 있어서, 그대로 두면 아무것도 강조하지 않은 채 설명만 뜬다.
     */
    @Test
    fun `대상이 없으면 그 단계를 건너뛴다`() {
        val anchors = setOf(
            CoachSteps.Summary.OVERVIEW,
            CoachSteps.Summary.EXIT,
        )
        val shown = visibleSteps(CoachSteps.summary, anchors)
        assertEquals(2, shown.size)
        assertEquals(CoachSteps.Summary.OVERVIEW, shown.first().anchor)
        assertEquals(CoachSteps.Summary.EXIT, shown.last().anchor)
    }

    @Test
    fun `대상이 하나도 없으면 아무 단계도 남지 않는다`() {
        assertTrue(visibleSteps(CoachSteps.history, emptySet()).isEmpty())
    }

    // ── 하지 않기로 한 말 ─────────────────────────────────

    /**
     * 전면·후면 전환은 **렌즈**를 바꾸는 기능이다. 정면·측면 측정 방향은 앱이 기준 자세를
     * 보면서 자동으로 판별한다 — 둘을 같은 말로 설명하면 사용자가 방향을 고르려 든다.
     */
    @Test
    fun `카메라 전환을 촬영 방향 선택이라고 하지 않는다`() {
        val hud = CoachSteps.workout.single { it.anchor == CoachSteps.Workout.HUD_ACTIONS }
        val text = hud.body + " " + hud.note.orEmpty()
        assertTrue(text.contains("렌즈"))
        assertTrue(text.contains("자동"))
    }

    /** 자세를 점수·등급으로 말하지 않는다. 이 앱은 사실만 낸다. */
    @Test
    fun `점수나 등급으로 설명하지 않는다`() {
        val text = (allSteps.flatMap { listOf(it.title, it.body, it.note.orEmpty()) } +
            OnboardingPages.flatMap { it.body + it.title + it.note.orEmpty() }).joinToString(" ")
        listOf("점수", "등급").forEach { banned ->
            assertFalse("금지어가 들어갔다: $banned", text.contains(banned))
        }
    }

    /**
     * 스쿼트를 **완전히 주저앉는 동작**으로 설명하지 않는다. 허벅지가 바닥과 평행해지는
     * 정도까지다.
     */
    @Test
    fun `완전히 앉으라고 하지 않는다`() {
        val text = (allSteps.map { it.body } + OnboardingPages.flatMap { it.body })
            .joinToString(" ")
        assertFalse(text.contains("완전히 앉"))
        assertFalse(text.contains("주저앉"))
    }

    /** 아직 완성되지 않은 동기화를 설명하지 않는다. */
    @Test
    fun `업로드 대기를 설명하지 않는다`() {
        val text = allSteps.joinToString(" ") { it.body + it.note.orEmpty() }
        assertFalse(text.contains("업로드"))
        assertFalse(text.contains("동기화"))
    }

    // ── 온보딩 ────────────────────────────────────────────

    @Test
    fun `온보딩은 네 쪽이다`() {
        assertEquals(4, OnboardingPages.size)
    }

    @Test
    fun `온보딩 각 쪽에 제목과 본문이 있다`() {
        OnboardingPages.forEach { page ->
            assertTrue(page.title.isNotBlank())
            assertTrue(page.body.isNotEmpty())
            assertTrue(page.body.all { it.isNotBlank() })
        }
    }

    /**
     * 운동 화면에 들어가자마자 센다고 하지 않는다 — 기준 자세를 잰 뒤부터 센다.
     * 3쪽이 그 순서를 말하는 쪽이다.
     */
    @Test
    fun `기준 자세를 잰 뒤부터 센다고 말한다`() {
        val page = OnboardingPages[2]
        assertTrue(page.body.any { it.contains("기준 자세 측정이 끝난 뒤부터") })
    }

    /** 사용자가 정면·측면을 고른다고 하지 않는다. */
    @Test
    fun `촬영 방향은 앱이 확인한다고 말한다`() {
        val page = OnboardingPages[2]
        assertTrue(page.body.any { it.contains("자동으로 확인") })
    }

    /** 칼로리는 어림값이고 신체 정보가 있어야 나온다는 사실을 밝힌다. */
    @Test
    fun `칼로리가 어림값임을 밝힌다`() {
        val note = OnboardingPages[1].note.orEmpty()
        assertTrue(note.contains("어림값"))
        assertTrue(note.contains("키와 몸무게"))
    }

    // ── 저장 키 ───────────────────────────────────────────

    /**
     * `메뉴 안내 다시 표시`는 **화면별 안내만** 지운다. 온보딩과 측정 준비까지 되살아나면
     * 화면 사용법을 다시 보려던 사람이 앱을 처음 켠 것처럼 안내를 다시 받는다.
     */
    @Test
    fun `메뉴 안내 초기화 대상은 화면별 안내뿐이다`() {
        assertEquals(
            listOf(
                GuideKey.HOME_TUTORIAL,
                GuideKey.WORKOUT_TUTORIAL,
                GuideKey.SUMMARY_TUTORIAL,
                GuideKey.HISTORY_TUTORIAL,
                GuideKey.SETTINGS_TUTORIAL,
            ),
            GuideKey.menuTutorials,
        )
        assertFalse(GuideKey.ONBOARDING.isMenuTutorial)
        assertFalse(GuideKey.MEASUREMENT_PREP.isMenuTutorial)
    }

    /** 키가 겹치면 한 안내를 본 것이 다른 안내를 본 것이 된다. */
    @Test
    fun `저장 키가 서로 다르다`() {
        val names = GuideKey.entries.map { it.prefName }
        assertEquals(names.size, names.toSet().size)
    }
}
