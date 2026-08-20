package com.mist.formchecker.capture

import android.content.Context
import com.mist.formchecker.poseengine.CaptureFrame
import com.mist.formchecker.poseengine.CaptureRep
import com.mist.formchecker.poseengine.CaptureSessionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 프레임 기록을 분석 스레드에서 떼어낸다.
 *
 * ## 왜 채널을 두는가
 * 프레임은 **초당 20~30번** 들어오고, rep이 끝날 때 수십 장이 한꺼번에 밀려든다. 분석
 * 스레드에서 직접 파일에 쓰면 그 프레임의 추론 시간에 디스크 I/O가 그대로 실리고, 배치가
 * 들어오는 프레임은 수십 배로 오염된다 — RTMPose가 이미 22~48ms를 쓰고 있어 여유가 없다.
 *
 * 채널 용량을 넉넉히 두되 무제한은 아니다. 디스크가 느려 밀리면 **오래된 프레임을 버리는
 * 대신 최신을 버린다**(`onBufferOverflow` 대신 명시적 처리) — 시계열 중간에 구멍이 나면
 * 진행도 계산이 틀리므로, 밀리는 상황이면 그 사실을 카운터로 남기는 편이 낫다.
 */
class CaptureLogSession(
    context: Context,
    scope: CoroutineScope,
) {
    private val writer = CaptureWriter(context)
    private val mailbox = Channel<Message>(CAPACITY)

    private val _writtenFrames = MutableStateFlow(0)

    /** 파일에 기록된 프레임 수. 화면에 표시해 기록되고 있음을 알린다. */
    val writtenFrames: StateFlow<Int> = _writtenFrames.asStateFlow()

    private val _droppedFrames = MutableStateFlow(0)

    /**
     * 채널이 밀려 버려진 프레임 수.
     *
     * 0이 아니면 시계열에 구멍이 있다는 뜻이므로 그 세션은 진행도 계산이 부정확하다.
     * 화면에 노출해 촬영자가 즉시 알 수 있어야 한다.
     */
    val droppedFrames: StateFlow<Int> = _droppedFrames.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (message in mailbox) {
                when (message) {
                    is Message.Open -> writer.open(message.record)
                    is Message.Frame -> {
                        writer.appendFrame(message.frame)
                        _writtenFrames.value = writer.writtenFrames
                    }
                    is Message.Reps -> {
                        writer.writeReps(message.reps)
                        // rep 경계에서만 flush한다. 프레임마다 하면 IO 스레드가
                        // 계속 디스크를 기다린다.
                        writer.flush()
                    }
                    Message.Close -> {
                        writer.close()
                        _writtenFrames.value = 0
                    }
                }
            }
        }
    }

    /** 세션 파일을 만든다. 캘리브레이션이 확정된 뒤 호출할 것. */
    fun open(record: CaptureSessionRecord) {
        mailbox.trySend(Message.Open(record))
    }

    /** 분석 스레드에서 호출한다. 블로킹하지 않는다. */
    fun logFrame(frame: CaptureFrame) {
        if (mailbox.trySend(Message.Frame(frame)).isFailure) {
            _droppedFrames.value += 1
        }
    }

    /** rep이 끝날 때, 그리고 라벨이 바뀔 때 호출한다. */
    fun logReps(reps: List<CaptureRep>) {
        mailbox.trySend(Message.Reps(reps))
    }

    fun finish() {
        mailbox.trySend(Message.Close)
    }

    private sealed interface Message {
        data class Open(val record: CaptureSessionRecord) : Message
        data class Frame(val frame: CaptureFrame) : Message
        data class Reps(val reps: List<CaptureRep>) : Message
        data object Close : Message
    }

    private companion object {
        /**
         * 약 18초 분량(30fps).
         *
         * 프레임이 낱개가 아니라 **배치로** 들어온다 — rep 하나(실측 ~24프레임)에 그 앞의
         * 대기 프레임(~25)까지 한 번에 밀려든다. 세션 종료 시 `drain()`은 마지막 rep 이후
         * 대기 구간을 통째로 보내므로 그보다 크다. 낱개로 들어올 때 기준(4초)으로는
         * 종료 배치에서 유실이 난다.
         *
         * 넘치면 그 사실을 알아야 하므로 무제한으로 두지는 않는다.
         */
        const val CAPACITY = 512
    }
}
