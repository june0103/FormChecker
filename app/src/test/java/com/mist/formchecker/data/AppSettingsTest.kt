package com.mist.formchecker.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
