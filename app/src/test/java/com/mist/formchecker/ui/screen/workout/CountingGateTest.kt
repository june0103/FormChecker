package com.mist.formchecker.ui.screen.workout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카운팅은 기준 자세를 다 잰 뒤에만 돌아간다.
 *
 * 이 규칙은 실기에서 유령 rep이 나와서 생겼다 — 카메라 앞으로 걸어가고 자리를 잡는 동안
 * 무릎 각도가 오르내려 하지 않은 rep이 세어졌다. 규칙을 되돌리려면 이 테스트가 함께
 * 깨져야 한다.
 *
 * 프레임 루프 자체(카메라·엔진)는 여기서 돌릴 수 없으므로, **게이트의 조건**을 붙든다.
 * `WorkoutViewModel.onResult`가 이 값으로 상태머신 입력을 막는다.
 */
class CountingGateTest {

    private fun stateAt(stage: CalibrationStage) =
        WorkoutUiState(calibrationStage = stage)

    @Test
    fun `기준 자세를 다 재면 센다`() {
        assertTrue(stateAt(CalibrationStage.READY).countingEnabled)
    }

    @Test
    fun `재기 전에는 세지 않는다`() {
        assertFalse(stateAt(CalibrationStage.NONE).countingEnabled)
    }

    /** 걸어오는 구간이 정확히 유령 rep이 나던 구간이다. */
    @Test
    fun `준비 시간에는 세지 않는다`() {
        assertFalse(stateAt(CalibrationStage.PREPARING).countingEnabled)
    }

    @Test
    fun `측정 중에는 세지 않는다`() {
        assertFalse(stateAt(CalibrationStage.COLLECTING).countingEnabled)
    }

    /**
     * 기본 상태에서 켜져 있으면 안 된다 — 화면에 들어온 직후가 걸어오는 구간이다.
     */
    @Test
    fun `기본값은 세지 않는 상태다`() {
        assertFalse(WorkoutUiState().countingEnabled)
    }

    /** 재측정을 시작하면 다시 꺼진다. 측정 창의 자세가 rep으로 세어지면 안 된다. */
    @Test
    fun `다시 재기 시작하면 꺼진다`() {
        val measured = stateAt(CalibrationStage.READY)
        assertTrue(measured.countingEnabled)
        assertFalse(measured.copy(calibrationStage = CalibrationStage.PREPARING).countingEnabled)
    }
}
