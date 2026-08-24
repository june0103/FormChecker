package com.mist.formchecker.audio

import android.media.ToneGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 소리 신호의 **의미 규약**.
 *
 * 실제 재생은 단위 테스트로 확인할 수 없다(`ToneGenerator`는 오디오 서비스가 필요하다).
 * 대신 소리끼리의 관계 — 어느 것이 긍정이고 어느 것이 부정인지, 짝이 되는 것들이 서로
 * 구분되는지 — 를 붙든다. 사용자는 소리만 듣고 판단하므로 그 관계가 규약이다.
 */
class AudioCuesTest {

    /** 세어졌다와 문제가 있었다는 **다른 소리**여야 한다. 같으면 구분할 수 없다. */
    @Test
    fun `rep 완주와 rep 경고는 다른 소리다`() {
        assertNotEquals(
            AudioCues.Cue.REP_DONE.toneType,
            AudioCues.Cue.REP_WARNED.toneType,
        )
    }

    /**
     * 둘은 `ACK`/`NACK` 짝이다 — 같은 계열의 긍정·부정이라 "세어졌다"는 사실은 유지하면서
     * "문제가 있었다"를 얹는다. 별개의 낮은 톤으로 바꾸면 세어졌는지가 흐려진다.
     */
    @Test
    fun `rep 소리는 ACK NACK 짝이다`() {
        assertEquals(ToneGenerator.TONE_PROP_ACK, AudioCues.Cue.REP_DONE.toneType)
        assertEquals(ToneGenerator.TONE_PROP_NACK, AudioCues.Cue.REP_WARNED.toneType)
    }

    /**
     * 문제를 알리는 소리는 **더 길다.** 운동 중에는 짧은 소리를 놓치기 쉽고, 놓쳐도 되는
     * 것은 "잘 됐다"뿐이다.
     */
    @Test
    fun `문제를 알리는 소리가 더 길다`() {
        assertTrue(
            AudioCues.Cue.REP_WARNED.durationMs > AudioCues.Cue.REP_DONE.durationMs,
        )
        assertTrue(
            AudioCues.Cue.FAILED.durationMs > AudioCues.Cue.CALIBRATED.durationMs,
        )
    }

    /** 카운트다운 틱은 가장 짧다 — 초마다 나므로 길면 시끄럽다. */
    @Test
    fun `카운트다운 틱이 가장 짧다`() {
        val shortest = AudioCues.Cue.entries.minBy { it.durationMs }
        assertEquals(AudioCues.Cue.COUNTDOWN, shortest)
    }

    /** 한 번만 울리는 것이 기본이다. 반복은 "놓치면 안 되는 것"에만 쓴다. */
    @Test
    fun `반복은 품질 이탈에만 쓴다`() {
        val repeated = AudioCues.Cue.entries.filter { it.repeats > 1 }
        assertEquals(listOf(AudioCues.Cue.QUALITY_LOST), repeated)
    }

    @Test
    fun `모든 소리는 길이와 반복이 유효하다`() {
        for (cue in AudioCues.Cue.entries) {
            assertTrue("$cue 길이", cue.durationMs > 0)
            assertTrue("$cue 반복", cue.repeats >= 1)
        }
    }
}
