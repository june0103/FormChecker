package com.mist.formchecker.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * 소리 신호.
 *
 * ## 왜 필수인가
 * **폰을 세워두고 몇 걸음 떨어져 쓰는 앱이다.** 수집 화면은 측면 촬영에서 화면을 아예 볼
 * 수 없고, 운동 화면은 그 거리에서 횟수 말고는 글씨가 읽히지 않는다(`기술선택_기록.md`
 * 57번). 시각 피드백을 대체할 채널이 있어야 한다.
 *
 * ## TTS를 쓰지 않는다
 * 초기화 지연·언어 데이터 의존 등 표면이 넓고, **한 번 읽는 데 rep 하나가 간다**(실측 rep
 * 중앙값 1,725ms). 상태 전이를 알리는 데는 톤 몇 개로 충분하고, 사용자는 신호를 몇 번
 * 들으면 바로 익힌다. 나레이션은 별도 작업으로 남겨 두었다.
 *
 * ## 스레드
 * `ToneGenerator`는 스레드 안전하지 않다. 예전에는 "UI 스레드에서만 호출할 것"이라고 적어
 * 두고 **실제로는 지키지 않았다** — 수집 화면이 rep 완주 톤을 카메라 분석 스레드에서,
 * 캘리브레이션 톤을 코루틴에서 재생해 두 스레드가 같은 인스턴스를 썼다.
 *
 * 규약으로 두면 호출부가 늘어날 때마다 다시 어긋난다. 그래서 **이 클래스가 직접 메인
 * 루퍼로 넘긴다.** 호출부는 아무 스레드에서나 불러도 된다.
 */
class AudioCues {

    private val handler = Handler(Looper.getMainLooper())

    /** 메인 스레드에서만 만지므로 동기화가 필요 없다. */
    private var generator: ToneGenerator? = null

    private fun tone(): ToneGenerator? {
        if (generator == null) {
            generator = runCatching {
                ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
            }.getOrNull()
        }
        return generator
    }

    fun play(cue: Cue) = onMain {
        val generator = tone() ?: return@onMain
        runCatching {
            repeat(cue.repeats) {
                generator.startTone(cue.toneType, cue.durationMs)
            }
        }
    }

    fun release() = onMain {
        runCatching { generator?.release() }
        generator = null
    }

    /**
     * 이미 메인이면 그대로 실행한다.
     *
     * 항상 `post`하면 rep 완주 톤이 한 프레임 늦는데, 카운터 숫자가 먼저 바뀌고 소리가
     * 뒤에 나면 어긋난 것으로 들린다.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    /**
     * 신호 종류.
     *
     * 높은 톤 = 됐다, 낮은 톤 = 문제. 소리만 듣고 "계속해도 되나"를 판단할 수 있어야 한다.
     */
    enum class Cue(val toneType: Int, val durationMs: Int, val repeats: Int) {
        /** 품질 검사 통과 — 캘리브레이션을 시작할 수 있다. */
        QUALITY_OK(ToneGenerator.TONE_PROP_BEEP, 120, 1),

        /** 캘리브레이션 카운트다운 한 칸. */
        COUNTDOWN(ToneGenerator.TONE_PROP_BEEP, 80, 1),

        /** 기준 자세 확보 — 이제부터 횟수를 센다. */
        CALIBRATED(ToneGenerator.TONE_PROP_BEEP2, 200, 1),

        /** 기준 자세 실패 — 자세나 카메라를 고쳐야 한다. */
        FAILED(ToneGenerator.TONE_CDMA_LOW_PBX_L, 350, 1),

        /** rep 완주, 지적할 것 없음. */
        REP_DONE(ToneGenerator.TONE_PROP_ACK, 90, 1),

        /**
         * rep 완주, 자세 문제 있음.
         *
         * [REP_DONE]의 짝이다 — `ACK`/`NACK`은 같은 계열의 긍정·부정 신호라 "세어졌다"는
         * 사실은 유지하면서 "그런데 문제가 있었다"를 얹는다. 별개의 낮은 톤을 쓰면
         * 세어졌는지 아닌지가 흐려진다.
         */
        REP_WARNED(ToneGenerator.TONE_PROP_NACK, 200, 1),

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
