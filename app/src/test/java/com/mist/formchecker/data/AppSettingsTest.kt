package com.mist.formchecker.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import com.mist.formchecker.poseengine.Sex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 설정 저장.
 *
 * ## 실제 DataStore로 돈다
 * 가짜 저장소를 만들면 **정작 확인해야 할 것을 확인하지 못한다** — 값이 디스크에 남는지,
 * 저장 전에 읽으면 무엇이 나오는지가 이 클래스의 전부다. 임시 폴더에 진짜 파일을 만든다.
 *
 * 프로덕션 코드(`SettingsModule`)와 **같은 팩토리**를 쓴다 — 직렬화 방식까지 같아야
 * 저장된 파일이 실제로 읽히는지 확인한 것이 된다.
 */
class AppSettingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun settings(name: String = "test"): AppSettings {
        val store: androidx.datastore.core.DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { folder.newFile("$name.preferences_pb") },
            )
        return AppSettings(store)
    }

    /**
     * 저장 전에는 null이다. **0을 돌려주면 안 된다** — 준비 시간 0초("바로")가 유효한
     * 선택지라서, 저장 안 함과 0초 선택이 구분되지 않으면 기본값을 쓸 수 없다.
     */
    @Test
    fun `저장하기 전에는 없음이다`() = runTest {
        assertNull(settings().calibrationPrepSeconds.first())
    }

    @Test
    fun `저장한 값을 읽는다`() = runTest {
        val store = settings()
        store.setCalibrationPrepSeconds(10)
        assertEquals(10, store.calibrationPrepSeconds.first())
    }

    /** 0초("바로")도 저장된 값이다. null과 구분돼야 한다. */
    @Test
    fun `0초도 저장된 값으로 남는다`() = runTest {
        val store = settings()
        store.setCalibrationPrepSeconds(0)
        assertEquals(0, store.calibrationPrepSeconds.first())
    }

    @Test
    fun `나중에 저장한 값이 이긴다`() = runTest {
        val store = settings()
        store.setCalibrationPrepSeconds(3)
        store.setCalibrationPrepSeconds(10)
        assertEquals(10, store.calibrationPrepSeconds.first())
    }

    /**
     * 같은 파일을 다시 열면 값이 남아 있어야 한다 — 앱을 닫았다 켠 상황이다.
     * 이게 이 클래스의 존재 이유이므로 파일 경로를 고정해 확인한다.
     *
     * ## 스코프를 닫고 다시 열어야 한다
     * 첫 인스턴스를 살려둔 채로 같은 파일을 또 열면 DataStore가 거부한다 —
     * `There are multiple DataStores active for the same file`. 이 테스트를 처음
     * 그렇게 썼다가 실제로 그 오류를 봤고, 그게 `SettingsModule`이 `@Singleton`이어야
     * 하는 이유이기도 하다.
     */
    @Test
    fun `앱을 다시 켜도 값이 남는다`() = runTest {
        val file = folder.newFile("persist.preferences_pb")

        val first = CoroutineScope(Dispatchers.IO + Job())
        AppSettings(PreferenceDataStoreFactory.create(scope = first, produceFile = { file }))
            .setCalibrationPrepSeconds(3)
        // 앱 종료에 해당한다. 닫지 않으면 다음 인스턴스가 같은 파일을 열 수 없다.
        first.cancel()

        val second = CoroutineScope(Dispatchers.IO + Job())
        val reopened =
            AppSettings(PreferenceDataStoreFactory.create(scope = second, produceFile = { file }))
        assertEquals(3, reopened.calibrationPrepSeconds.first())
        second.cancel()
    }

    // ── 신체 정보: 반쯤 입력된 상태 ──────────────────────────
    //
    // 이 묶음 전체가 **실기기에서 데이터를 잃고 나서** 생겼다. 저장소가 키·몸무게를 한 번에
    // 받고 하나라도 없으면 null만 내보내던 때, 설정 화면은 저장된 값을 되보여 줄 수 없었고
    // 그래서 빈 칸으로 덮어썼다. 재현: body_height_cm=175만 있는 상태에서 몸무게 70 입력
    // → 파일에서 body_height_cm이 사라짐.

    /**
     * **이 테스트가 그 버그다.** 몸무게를 저장해도 키가 남아 있어야 한다.
     *
     * 예전 `setBodyProfile(heightCm, weightKg)`는 둘을 함께 썼다. 화면의 키 칸이 비어
     * 있으면(=저장된 키를 못 받아서 비어 보이면) 몸무게를 넣는 순간 null이 함께 저장돼
     * 디스크의 키가 지워졌다.
     */
    @Test
    fun `몸무게를 저장해도 키가 지워지지 않는다`() = runTest {
        val store = settings()
        store.setHeightCm(175f)
        store.setWeightKg(70f)

        val input = store.bodyInput.first()
        assertEquals(175f, input.heightCm!!, 0.01f)
        assertEquals(70f, input.weightKg!!, 0.01f)
    }

    /** 반대 방향도 같다. 값마다 자기 것만 쓴다. */
    @Test
    fun `키를 저장해도 몸무게가 지워지지 않는다`() = runTest {
        val store = settings()
        store.setWeightKg(70f)
        store.setHeightCm(175f)

        assertEquals(70f, store.bodyInput.first().weightKg!!, 0.01f)
    }

    /**
     * 반쯤 입력된 상태가 **원값으로는 보이고** 프로필로는 안 보여야 한다.
     *
     * 화면은 원값으로 입력칸을 채우고, 열량 계산은 프로필로 막는다. 이 둘을 하나로 합쳤던
     * 것이 위 데이터 손실의 원인이었다 — 감춘 상태는 복원할 수 없고, 복원하지 못한 값은
     * 다음 입력에 덮어써진다.
     */
    @Test
    fun `키만 있으면 원값은 보이고 프로필은 없다`() = runTest {
        val store = settings()
        store.setHeightCm(175f)

        val input = store.bodyInput.first()
        assertEquals(175f, input.heightCm!!, 0.01f)
        assertNull(input.weightKg)
        assertNull("키만으로는 열량을 계산할 수 없다", input.profile)
        assertNull(store.bodyProfile.first())
    }

    /** 나이·성별만 넣어도 열량은 계산할 수 없다. 게이트는 키·몸무게다. */
    @Test
    fun `나이와 성별만으로는 프로필이 없다`() = runTest {
        val store = settings()
        store.setAge(35)
        store.setSex(Sex.FEMALE)

        val input = store.bodyInput.first()
        assertEquals(35, input.age)
        assertEquals(Sex.FEMALE, input.sex)
        assertNull(input.profile)
    }

    /** 키·몸무게가 둘 다 있으면 프로필이 나오고, 선택값도 함께 실린다. */
    @Test
    fun `키와 몸무게가 있으면 프로필이 나온다`() = runTest {
        val store = settings()
        store.setHeightCm(175f)
        store.setWeightKg(70f)
        store.setAge(35)
        store.setSex(Sex.MALE)

        val profile = store.bodyProfile.first()!!
        assertEquals(175f, profile.heightCm, 0.01f)
        assertEquals(70f, profile.weightKg, 0.01f)
        assertEquals(35, profile.age)
        assertEquals(Sex.MALE, profile.sex)
    }

    /** 지우는 길이 남아 있어야 한다 — 한 번 넣으면 못 지우는 신체 정보는 되돌릴 수 없다. */
    @Test
    fun `null을 주면 그 값만 지운다`() = runTest {
        val store = settings()
        store.setHeightCm(175f)
        store.setWeightKg(70f)

        store.setWeightKg(null)

        val input = store.bodyInput.first()
        assertEquals(175f, input.heightCm!!, 0.01f)
        assertNull(input.weightKg)
    }

    /** 저장 전에는 네 값이 모두 없다. 그 상태가 정상이다 — 열량은 부가 기능이다. */
    @Test
    fun `저장하기 전에는 신체 정보가 비어 있다`() = runTest {
        val input = settings().bodyInput.first()
        assertNull(input.heightCm)
        assertNull(input.weightKg)
        assertNull(input.age)
        assertNull(input.sex)
        assertNull(input.profile)
    }

    // ── 기준 완화 ────────────────────────────────────────────

    /**
     * **저장 전에는 꺼짐이다.** 확정된 임계값이 기본이어야 하고, 완화는 사용자가 고르는
     * 것이다 — 반대로 켜져 있으면 아무도 고르지 않은 완화 기준으로 판정이 돌아간다.
     */
    @Test
    fun `기준 완화는 기본이 꺼짐이다`() = runTest {
        assertFalse(settings().relaxedForm.first())
    }

    @Test
    fun `기준 완화를 켜면 남는다`() = runTest {
        val store = settings()
        store.setRelaxedForm(true)
        assertTrue(store.relaxedForm.first())
    }

    /** 껐다는 사실도 저장돼야 한다 — 지우면 "저장 전"과 같아지므로 결과는 같지만, 명시적으로 끈 것이 유지되는지 확인한다. */
    @Test
    fun `기준 완화를 다시 끄면 꺼진다`() = runTest {
        val store = settings()
        store.setRelaxedForm(true)
        store.setRelaxedForm(false)
        assertFalse(store.relaxedForm.first())
    }

    /** 켜 둔 상태로 앱을 닫았다 켜면 그대로여야 한다 — 이게 설정인 이유다. */
    @Test
    fun `앱을 다시 켜도 기준 완화가 남는다`() = runTest {
        val file = folder.newFile("relaxed.preferences_pb")

        val first = CoroutineScope(Dispatchers.IO + Job())
        AppSettings(PreferenceDataStoreFactory.create(scope = first, produceFile = { file }))
            .setRelaxedForm(true)
        first.cancel()

        val second = CoroutineScope(Dispatchers.IO + Job())
        val reopened =
            AppSettings(PreferenceDataStoreFactory.create(scope = second, produceFile = { file }))
        assertTrue(reopened.relaxedForm.first())
        second.cancel()
    }

    // ── 하루 목표 ─────────────────────────────────────────

    /**
     * 저장 전에는 null이다. **기본 목표를 저장소가 지어내면 안 된다** — 사용자가 정하지도
     * 않은 숫자에 미달한 상태로 홈이 열린다.
     */
    @Test
    fun `목표를 정하기 전에는 없음이다`() = runTest {
        assertNull(settings().dailyRepGoal.first())
    }

    @Test
    fun `정한 목표를 읽는다`() = runTest {
        val store = settings()
        store.setDailyRepGoal(30)
        assertEquals(30, store.dailyRepGoal.first())
    }

    /** 범위 밖은 저장하지 않는다. 오타로 들어온 값이 디스크에 남으면 화면에서 되돌릴 수 없다. */
    @Test
    fun `범위를 벗어난 목표는 저장하지 않는다`() = runTest {
        val store = settings()
        store.setDailyRepGoal(30)
        store.setDailyRepGoal(0)
        store.setDailyRepGoal(DailyGoal.RANGE.last + 1)
        assertEquals(30, store.dailyRepGoal.first())
    }

    @Test
    fun `목표를 지우면 다시 없음이 된다`() = runTest {
        val store = settings()
        store.setDailyRepGoal(30)
        store.clearDailyRepGoal()
        assertNull(store.dailyRepGoal.first())
    }

    /** 증감 버튼이 경계를 넘지 않는다. */
    @Test
    fun `증감은 범위 안에서 움직인다`() {
        assertEquals(DailyGoal.STEP, DailyGoal.step(0, DailyGoal.STEP))
        assertEquals(DailyGoal.RANGE.first, DailyGoal.step(1, -DailyGoal.STEP))
        assertEquals(DailyGoal.RANGE.last, DailyGoal.step(DailyGoal.RANGE.last, DailyGoal.STEP))
    }

    // ── 동작 예시 최초 안내 ───────────────────────────────

    /** 설치 직후에는 아직 못 봤다. 이 값이 false여야 처음 한 번이 뜬다. */
    @Test
    fun `처음에는 동작 예시를 본 적이 없다`() = runTest {
        assertFalse(settings().squatExampleSeen.first())
    }

    @Test
    fun `동작 예시를 보면 기억한다`() = runTest {
        val store = settings()
        store.markSquatExampleSeen()
        assertTrue(store.squatExampleSeen.first())
    }
}
