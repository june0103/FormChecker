package com.mist.formchecker.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 수집 파일을 다른 앱으로 넘기는 인텐트를 만든다.
 *
 * ## 왜 FileProvider인가
 * Android 7부터 `file://` URI를 앱 밖으로 넘기면 `FileUriExposedException`이 난다.
 * `content://` URI와 일회성 읽기 권한으로 넘겨야 한다.
 *
 * ## 왜 공유 기능이 필요한가
 * `adb pull`로도 꺼낼 수 있지만 촬영자가 개발 환경 앞에 없을 수 있고, 촬영 직후 바로
 * 넘기는 것이 왕복을 줄인다.
 */
object CaptureShare {

    /** 저장된 모든 수집 파일을 공유하는 인텐트. 파일이 없으면 null. */
    fun intentFor(context: Context): Intent? {
        val files = CaptureWriter.storedFiles(context)
        if (files.isEmpty()) return null

        val uris = files.mapNotNull { uriFor(context, it) }
        if (uris.isEmpty()) return null

        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "폼체커 기준 자세 수집 데이터 (${files.size}개 파일)")
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    appendLine("세션마다 session / frames / reps 세 종류가 있습니다.")
                    appendLine("CSV와 JSONL의 컬럼은 동일하며 session_id·rep_id로 조인합니다.")
                    appendLine()
                    appendLine("- 좌표는 프레임 기준 0~1 정규화이며 비등방입니다.")
                    appendLine("  x·y를 섞어 거리를 구하려면 x에 frame_aspect_ratio를 곱하세요.")
                    appendLine("- kp*_raw_*는 필터 전, kp*_x/y는 필터 후 좌표입니다.")
                    appendLine("- 빈 칸은 판정 불가입니다(신뢰도 미달 또는 해당 각도 미지원).")
                    appendLine("  추정값을 채우지 않았습니다.")
                    appendLine("- phase_progress는 시간이 아니라 무릎 굴곡 기준입니다.")
                    appendLine("- 거리 특징의 분모는 session.csv의 캘리브레이션 길이입니다.")
                },
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(share, "수집 데이터 보내기")
    }

    private fun uriFor(context: Context, file: File) = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
