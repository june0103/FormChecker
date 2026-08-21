package com.mist.formchecker.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 세션 목록 요약.
 *
 * ## 왜 테스트하는가
 * 이 목록을 보고 **세션을 지운다.** 열이 밀려서 다른 세션의 정보가 표시되면 지우면 안 되는
 * 것을 지우게 되고, 촬영 데이터는 다시 만들려면 사람을 다시 모아야 하는 종류의 파일이다.
 * 그리고 파싱 오류는 값이 그럴듯해서 조용히 틀린다.
 */
class CaptureLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun session(
        name: String,
        sessionCsv: String,
        repsCsv: String? = null,
    ): File {
        val dir = folder.newFolder(name)
        File(dir, "session.csv").writeText(sessionCsv)
        repsCsv?.let { File(dir, "reps.csv").writeText(it) }
        return dir
    }

    private val header = "session_id,person_id,view,footwear,captured_at"

    @Test
    fun `세션 메타와 rep 수를 읽는다`() {
        val dir = session(
            name = "capture_1",
            sessionCsv = "$header\nabc,P001,SIDE_LEFT,BAREFOOT,2026-08-20T21:30:49\n",
            repsCsv = "rep_id,include_in_reference\n1,1\n2,0\n3,1\n",
        )

        val summary = CaptureLibrary.summarize(dir)!!
        assertEquals("P001", summary.personId)
        assertEquals("SIDE_LEFT", summary.view)
        assertEquals("BAREFOOT", summary.footwear)
        assertEquals(3, summary.reps)
        assertEquals(2, summary.includedReps)
        assertEquals("P001 · SIDE_LEFT · BAREFOOT", summary.label)
    }

    /**
     * `person_id`는 사용자가 입력하는 값이고 `CaptureExport`는 쉼표가 든 값을 인용부호로
     * 감싼다. `split(",")`로 나누면 그 뒤 열이 전부 한 칸씩 밀려 `view` 자리에 신발이,
     * 신발 자리에 시각이 들어온다 — 목록이 그럴듯하게 틀린다.
     */
    @Test
    fun `인용부호 안의 쉼표에 열이 밀리지 않는다`() {
        val dir = session(
            name = "capture_2",
            sessionCsv = "$header\nabc,\"P001,test\",FRONT,SHOES,2026-08-20T11:00:00\n",
        )

        val summary = CaptureLibrary.summarize(dir)!!
        assertEquals("P001,test", summary.personId)
        assertEquals("FRONT", summary.view)
        assertEquals("SHOES", summary.footwear)
    }

    /** 두 개 연속 인용부호는 값 안의 인용부호 하나다. */
    @Test
    fun `값 안의 인용부호를 복원한다`() {
        val dir = session(
            name = "capture_3",
            sessionCsv = "$header\nabc,\"P\"\"001\",FRONT,SHOES,2026-08-20T11:00:00\n",
        )

        assertEquals("P\"001", CaptureLibrary.summarize(dir)!!.personId)
    }

    /** session.csv가 없으면 세션이 아니다. 다른 폴더가 섞여 들어와도 목록이 깨지지 않아야 한다. */
    @Test
    fun `세션 메타가 없으면 요약하지 않는다`() {
        val dir = folder.newFolder("not_a_session")
        File(dir, "frames.csv").writeText("frame_index\n1\n")

        assertNull(CaptureLibrary.summarize(dir))
    }

    /** 헤더만 있고 값이 없으면 세션으로 보지 않는다. */
    @Test
    fun `헤더만 있는 메타는 요약하지 않다`() {
        val dir = session(name = "capture_4", sessionCsv = "$header\n")

        assertNull(CaptureLibrary.summarize(dir))
    }

    /**
     * rep 파일이 없어도 목록에는 나와야 한다 — 촬영이 중간에 끊긴 세션이 그렇게 되고,
     * 그게 바로 지워야 하는 세션이다. 목록에서 빠지면 지울 방법이 없다.
     */
    @Test
    fun `rep 파일이 없어도 세션은 목록에 남는다`() {
        val dir = session(name = "capture_5", sessionCsv = "$header\nabc,P002,FRONT,,\n")

        val summary = CaptureLibrary.summarize(dir)!!
        assertEquals("P002", summary.personId)
        assertEquals(0, summary.reps)
        assertEquals(0, summary.includedReps)
        // 신발이 비면 라벨에서 빠진다. "?"로 채우면 값이 있는 것처럼 보인다.
        assertEquals("P002 · FRONT", summary.label)
    }

    /**
     * 옛 빌드로 찍은 세션은 `include_in_reference` 컬럼이 없다. 그때 포함 수를 rep 수와 같게
     * 두면 쓸 수 없는 세션이 쓸 만해 보인다.
     */
    @Test
    fun `포함 컬럼이 없으면 포함 수를 0으로 둔다`() {
        val dir = session(
            name = "capture_6",
            sessionCsv = "$header\nabc,P001,FRONT,SHOES,2026-08-20T11:00:00\n",
            repsCsv = "rep_id,counted\n1,1\n2,1\n",
        )

        val summary = CaptureLibrary.summarize(dir)!!
        assertEquals(2, summary.reps)
        assertEquals(0, summary.includedReps)
    }

    @Test
    fun `폴더 크기를 합산한다`() {
        val dir = session(
            name = "capture_7",
            sessionCsv = "$header\nabc,P001,FRONT,SHOES,2026-08-20T11:00:00\n",
            repsCsv = "rep_id,include_in_reference\n1,1\n",
        )
        val expected = dir.listFiles()!!.sumOf { it.length() }

        assertEquals(expected, CaptureLibrary.summarize(dir)!!.bytes)
    }

    @Test
    fun `세션을 지우면 폴더가 사라진다`() {
        val dir = session(
            name = "capture_8",
            sessionCsv = "$header\nabc,P001,FRONT,SHOES,2026-08-20T11:00:00\n",
            repsCsv = "rep_id,include_in_reference\n1,1\n",
        )
        val summary = CaptureLibrary.summarize(dir)!!

        assertEquals(true, CaptureLibrary.delete(summary))
        assertEquals(false, dir.exists())
    }
}
