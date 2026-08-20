package com.mist.formchecker.capture

import android.content.Context
import android.util.Log
import com.mist.formchecker.poseengine.CaptureExport
import com.mist.formchecker.poseengine.CaptureFrame
import com.mist.formchecker.poseengine.CaptureRep
import com.mist.formchecker.poseengine.CaptureSessionRecord
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 수집 세션을 파일로 쓴다.
 *
 * ## 왜 프레임을 스트리밍으로 쓰는가
 * rep 하나가 50~100프레임이고 한 세션이 30 rep이면 3000행이다. 메모리에 다 쌓아두고
 * 종료 시 한 번에 쓰면 **앱이 죽거나 배터리가 끊기는 순간 세션 전체가 사라진다.**
 * 촬영은 되돌리기가 가장 비싼 작업이라 프레임마다 이어 쓴다. 버퍼는 두되 rep 경계에서
 * flush한다 — 프레임마다 flush하면 분석 스레드에 디스크 지연이 실린다.
 *
 * ## 파일 구성
 * ```
 * capture_<시각>_<뷰>_<세션8자>/
 *   session.csv   session.jsonl    세션 메타 + 캘리브레이션 (1행)
 *   frames.csv    frames.jsonl     프레임별 26점 원본·필터 좌표 + 특징
 *   reps.csv      reps.jsonl       rep 요약 + 라벨
 * ```
 * `session_id`와 `rep_id`로 조인한다.
 *
 * 스레드 안전하지 않다. [CaptureLogSession]을 통해서만 쓸 것.
 */
class CaptureWriter(private val context: Context) {

    private var directory: File? = null
    private var frameCsv: BufferedWriter? = null
    private var frameJsonl: BufferedWriter? = null
    private var sessionId: String = ""
    private var framesWritten = 0

    val files: List<File> get() = directory?.listFiles()?.toList().orEmpty()
    val writtenFrames: Int get() = framesWritten

    /**
     * 세션 디렉터리와 프레임 파일을 만든다.
     *
     * 세션 메타는 캘리브레이션이 확정된 뒤에만 쓸 수 있으므로 [writeSession]에서 따로 쓴다.
     */
    fun open(record: CaptureSessionRecord) {
        if (directory != null) return
        sessionId = record.info.sessionId

        val root = File(context.getExternalFilesDir(null), DIR_NAME)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(root, "capture_${stamp}_${record.info.view.name}_${sessionId.take(8)}")
        if (!dir.mkdirs() && !dir.isDirectory) {
            Log.w(TAG, "수집 디렉터리를 만들 수 없습니다: $dir")
            return
        }

        runCatching {
            directory = dir
            writeSession(record)
            frameCsv = File(dir, "frames.csv").bufferedWriter().apply {
                write(CaptureExport.frameCsvHeader()); newLine()
            }
            frameJsonl = File(dir, "frames.jsonl").bufferedWriter()
            framesWritten = 0
            Log.i(TAG, "수집 시작: ${dir.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "수집 파일을 만들 수 없습니다", e)
            close()
        }
    }

    /** 세션 메타 + 캘리브레이션. 1행이라 매번 덮어쓴다. */
    private fun writeSession(record: CaptureSessionRecord) {
        val dir = directory ?: return
        File(dir, "session.csv").writeText(
            CaptureExport.sessionCsvHeader() + "\n" + CaptureExport.sessionCsvRow(record) + "\n",
        )
        File(dir, "session.jsonl").writeText(CaptureExport.sessionJsonLine(record) + "\n")
    }

    fun appendFrame(frame: CaptureFrame) {
        val csv = frameCsv ?: return
        val jsonl = frameJsonl ?: return
        try {
            csv.write(CaptureExport.frameCsvRow(sessionId, frame)); csv.newLine()
            jsonl.write(CaptureExport.frameJsonLine(sessionId, frame)); jsonl.newLine()
            framesWritten++
        } catch (e: IOException) {
            // 로그 기록 실패로 촬영이 중단되면 안 된다. 저장 공간이 없다고 세션이
            // 끊기는 것보다 그 rep을 잃는 편이 낫다.
            Log.w(TAG, "프레임 ${frame.frameIndex} 기록 실패", e)
        }
    }

    /** rep 경계에서 호출한다. 여기서 flush해 앱이 죽어도 완주한 rep은 남는다. */
    fun flush() {
        runCatching {
            frameCsv?.flush()
            frameJsonl?.flush()
        }
    }

    /**
     * rep 목록을 다시 쓴다.
     *
     * 라벨은 검토 화면에서 나중에 바뀌므로 rep 파일은 **매번 전체를 덮어쓴다.** 프레임과
     * 달리 행 수가 수십이라 비용이 없다.
     */
    fun writeReps(reps: List<CaptureRep>) {
        val dir = directory ?: return
        runCatching {
            File(dir, "reps.csv").bufferedWriter().use { out ->
                out.write(CaptureExport.repCsvHeader()); out.newLine()
                reps.forEach { out.write(CaptureExport.repCsvRow(sessionId, it)); out.newLine() }
            }
            File(dir, "reps.jsonl").bufferedWriter().use { out ->
                reps.forEach { out.write(CaptureExport.repJsonLine(sessionId, it)); out.newLine() }
            }
        }.onFailure { e -> Log.w(TAG, "rep 기록 실패", e) }
    }

    /** 프레임이 하나도 없으면 디렉터리를 지운다. 화면만 열었다 닫은 세션이 쌓이지 않게. */
    fun close() {
        runCatching { frameCsv?.close() }
        runCatching { frameJsonl?.close() }
        frameCsv = null
        frameJsonl = null
        if (framesWritten == 0) {
            directory?.deleteRecursively()
        }
        directory = null
        framesWritten = 0
    }

    companion object {
        private const val TAG = "CaptureWriter"
        const val DIR_NAME = "captures"

        /** 저장된 세션 디렉터리. 최신 순. */
        fun storedSessions(context: Context): List<File> {
            val root = File(context.getExternalFilesDir(null), DIR_NAME)
            return root.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        }

        /** 모든 세션의 모든 파일. 공유용. */
        fun storedFiles(context: Context): List<File> =
            storedSessions(context).flatMap { it.listFiles()?.toList().orEmpty() }
                .filter { it.isFile && it.length() > 0 }

        fun directoryPath(context: Context): String =
            File(context.getExternalFilesDir(null), DIR_NAME).absolutePath
    }
}
