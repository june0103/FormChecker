package com.mist.formchecker.capture

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 촬영 중 소리 신호.
 *
 * ## 왜 필수인가
 * 측면 촬영은 폰을 옆에 세워두고 진행하므로 **촬영 중 화면을 볼 수 없다.**
 * 캘리브레이션이 실패한 것을 모르고 30 rep을 찍으면 전부 버려야 한다 — 이것이 수집에서
 * 가장 비싼 실패 모드다. 시각 피드백을 대체할 채널이 있어야 한다.
 *
 * TTS를 쓰지 않는 이유: 초기화 지연·언어 설정 의존·권한 등 표면이 넓다. 상태 전이를
 * 알리는 데는 톤 몇 개로 충분하고, 촬영자는 신호를 몇 번 들으면 바로 익힌다.
 *
 * `ToneGenerator`는 스레드 안전하지 않으므로 UI 스레드에서만 호출할 것.
 */
class CaptureCues {

    private var generator: ToneGenerator? = null

    private fun tone(): ToneGenerator? {
        if (generator == null) {
            generator = runCatching {
                ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
            }.getOrNull()
        }
        return generator
    }

    fun play(cue: Cue) {
        val generator = tone() ?: return
        runCatching {
            repeat(cue.repeats) {
                generator.startTone(cue.toneType, cue.durationMs)
            }
        }
    }

    fun release() {
        runCatching { generator?.release() }
        generator = null
    }

    /**
     * 신호 종류.
     *
     * 높은 톤 = 진행 가능, 낮은 톤 = 문제. 촬영자가 소리만 듣고 "계속해도 되나"를
     * 판단할 수 있어야 한다.
     */
    enum class Cue(val toneType: Int, val durationMs: Int, val repeats: Int) {
        /** 품질 검사 통과 — 캘리브레이션을 시작할 수 있다. */
        QUALITY_OK(ToneGenerator.TONE_PROP_BEEP, 120, 1),

        /** 캘리브레이션 카운트다운 한 칸. */
        COUNTDOWN(ToneGenerator.TONE_PROP_BEEP, 80, 1),

        /** 캘리브레이션 통과 — 스쿼트를 시작해도 된다. */
        CALIBRATED(ToneGenerator.TONE_PROP_BEEP2, 200, 1),

        /** 캘리브레이션 실패 — 카메라를 고쳐야 한다. */
        FAILED(ToneGenerator.TONE_CDMA_LOW_PBX_L, 350, 1),

        /** rep 완주. */
        REP_DONE(ToneGenerator.TONE_PROP_ACK, 90, 1),

        /** 촬영 중 품질이 깨졌다 — 프레임 이탈, 각도 어긋남 등. */
        QUALITY_LOST(ToneGenerator.TONE_CDMA_LOW_PBX_L, 250, 2),

        /** 세션 종료. */
        SESSION_END(ToneGenerator.TONE_PROP_PROMPT, 250, 1),
    }

    private companion object {
        /** 최대(100)로 두지 않는다 — 운동 중 갑작스러운 큰 소리는 놀라게 한다. */
        const val VOLUME = 70
    }
}
