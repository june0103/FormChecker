package com.mist.formchecker.capture

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 기기에 저장된 수집 세션을 훑고 지운다.
 *
 * ## 왜 필요한가
 * 촬영은 실패한다 — 자세가 엉망이었거나, 사람이 프레임을 벗어났거나, `person_id`를 바꾸는
 * 것을 잊었거나. 프레임이 0인 세션은 [CaptureWriter]가 자동으로 지우지만 그건 화면만 열었다
 * 닫은 경우다. **찍긴 찍었는데 버려야 하는 세션은 기기에 그대로 남는다.**
 *
 * 남으면 두 가지가 나빠진다. 공유하면 전부 딸려 나가 분석 쪽에서 골라내야 하고, 다음 촬영
 * 때 "이게 아까 찍은 건가"를 폴더명으로 구분해야 한다. 12~18세션을 찍을 계획이면 그 부담이
 * 실제로 생긴다.
 *
 * ## 왜 frames.csv를 읽지 않는가
 * 프레임 파일은 세션당 1.7MB다. 목록을 띄울 때마다 전부 읽으면 세션 18개에서 30MB를
 * 훑는다. rep 수·포함 수는 `reps.csv`(4KB)에 있고, 프레임 규모는 파일 크기로 충분히
 * 가늠된다.
 */
object CaptureLibrary {

    private const val TAG = "CaptureLibrary"

    /**
     * 세션 하나의 요약.
     *
     * @property includedReps 기준 표본에 포함된 rep 수. 이 값이 0이면 찍었지만 쓸 것이 없는
     *   세션이므로 지울 후보다.
     */
    data class StoredSession(
        val directory: File,
        val personId: String,
        val view: String,
        val footwear: String,
        val capturedAt: String,
        val reps: Int,
        val includedReps: Int,
        val bytes: Long,
    ) {
        val name: String get() = directory.name

        /** 목록에 보여줄 한 줄. 조건이 섞였는지 눈에 보이게 사람·각도·신발을 함께 둔다. */
        val label: String
            get() = buildString {
                append(personId.ifEmpty { "?" })
                append(" · ").append(view.ifEmpty { "?" })
                if (footwear.isNotEmpty()) append(" · ").append(footwear)
            }
    }

    /** 저장된 세션을 최신 순으로 훑는다. 파일을 읽으므로 IO 스레드에서 호출할 것. */
    fun list(context: Context): List<StoredSession> =
        CaptureWriter.storedSessions(context).mapNotNull(::summarize)

    /**
     * 세션 폴더를 지운다.
     *
     * 되돌릴 수 없다 — 호출하는 쪽이 확인을 받아야 한다. 여기서 확인을 처리하지 않는 이유는
     * 확인 UI가 화면 몫이고, 이 객체가 그걸 알면 테스트도 화면을 끌고 와야 한다.
     */
    fun delete(session: StoredSession): Boolean {
        val ok = session.directory.deleteRecursively()
        if (!ok) Log.w(TAG, "세션을 지우지 못했습니다: ${session.name}")
        return ok
    }

    /**
     * 폴더 하나를 요약한다. 컨텍스트가 필요 없어 단위테스트로 검증할 수 있다 —
     * 열 밀림 같은 파싱 오류는 값이 그럴듯해서 조용히 틀린다.
     */
    internal fun summarize(directory: File): StoredSession? {
        val meta = firstRow(File(directory, "session.csv")) ?: return null
        val reps = rows(File(directory, "reps.csv"))
        val includeIndex = reps.firstOrNull()?.indexOf("include_in_reference") ?: -1

        return StoredSession(
            directory = directory,
            personId = meta["person_id"].orEmpty(),
            view = meta["view"].orEmpty(),
            footwear = meta["footwear"].orEmpty(),
            capturedAt = meta["captured_at"].orEmpty(),
            // 헤더를 뺀 나머지가 rep 행이다.
            reps = (reps.size - 1).coerceAtLeast(0),
            includedReps = if (includeIndex < 0) {
                0
            } else {
                reps.drop(1).count { it.getOrNull(includeIndex) == "1" }
            },
            bytes = directory.listFiles()?.sumOf { it.length() } ?: 0L,
        )
    }

    private fun firstRow(file: File): Map<String, String>? {
        val rows = rows(file)
        if (rows.size < 2) return null
        return rows[0].zip(rows[1]).toMap()
    }

    private fun rows(file: File): List<List<String>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.useLines { lines -> lines.filter { it.isNotBlank() }.map(::splitCsv).toList() }
        }.onFailure { Log.w(TAG, "읽지 못했습니다: ${file.name}", it) }.getOrDefault(emptyList())
    }

    /**
     * CSV 한 줄을 나눈다. 인용부호 안의 쉼표를 지킨다.
     *
     * `split(",")`로 충분해 보이지만 `person_id`는 사용자가 입력하는 값이고
     * [CaptureExport]는 쉼표가 든 값을 인용부호로 감싼다. 그때 열이 밀려서 `view` 자리에
     * 엉뚱한 값이 들어오면 목록이 조용히 틀린다.
     */
    private fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += field.toString()
                    field.clear()
                }
                else -> field.append(char)
            }
            index++
        }
        fields += field.toString()
        return fields
    }
}
