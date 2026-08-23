package com.mist.formchecker.poseengine

/**
 * 수집 데이터를 CSV·JSONL로 내보낸다 (문서 §18).
 *
 * ## 세 파일로 나누는 이유
 * 프레임·rep·세션은 행 수가 3~4자리씩 다르다. 한 파일에 합치면 세션 메타가 프레임마다
 * 반복되어 파일이 몇 배로 커지고, rep 단위 분석을 하려면 매번 중복을 제거해야 한다.
 * `session_id`와 `rep_id`로 조인한다.
 *
 * ## 왜 CSV와 JSONL을 모두 내보내는가
 * 컬럼 정의를 한곳에서 파생시켜 두 포맷이 어긋날 수 없게 만든다. CSV는 스프레드시트로 바로
 * 열리고, JSONL은 **빈 값과 0을 타입 수준에서 구분**한다(CSV의 빈 칸은 `NaN`으로만 읽힌다).
 * 빈 칸이 "판정 불가"를 뜻하는 이 데이터에서는 그 구분이 중요하다.
 */
object CaptureExport {

    private class Column<T>(val name: String, val extract: (T) -> Any?)

    // ── sessions ────────────────────────────────────────────

    private val SESSION_COLUMNS: List<Column<CaptureSessionRecord>> = buildList {
        fun info(name: String, extract: (CaptureSessionInfo) -> Any?) =
            add(Column<CaptureSessionRecord>(name) { extract(it.info) })

        fun calib(name: String, extract: (StandingCalibration) -> Any?) =
            add(Column<CaptureSessionRecord>(name) { extract(it.calibration) })

        info("session_id") { it.sessionId }
        info("measurement_group_id") { it.measurementGroupId }
        info("person_id") { it.personId }
        info("view") { it.view.name }
        info("active_side") { it.view.activeSide?.name }
        info("exercise_profile") { it.exerciseProfile }
        info("footwear") { it.footwear.name }
        info("camera_height_cm") { it.cameraHeightCm }
        info("camera_distance_cm") { it.cameraDistanceCm }
        info("device_model") { it.deviceModel }
        info("pose_model") { it.poseModelName }
        info("frame_width") { it.frameWidth }
        info("frame_height") { it.frameHeight }
        // x에 곱하면 등방 좌표가 된다. 이 보정을 빼먹는 것이 가장 흔한 실수라 컬럼으로 둔다.
        info("frame_aspect_ratio") { it.aspectRatio }
        info("analysis_fps") { it.analysisFps }
        info("captured_at") { it.capturedAt }

        // ── 캘리브레이션: 모든 거리 특징의 분모 ─────────────
        calib("femur_length") { it.femurLength }
        calib("tibia_length") { it.tibiaLength }
        calib("leg_length") { it.legLength }
        // 체형 변수. 임계값을 다른 사람에게 옮길 수 있는지 판단하는 근거다.
        calib("femur_tibia_ratio") { it.femurTibiaRatio }
        calib("hip_width") { it.hipWidth }
        calib("shoulder_width") { it.shoulderWidth }
        calib("trunk_length") { it.trunkLength }
        calib("foot_length") { it.footLength }
        calib("standing_knee_flexion") { it.standingKneeFlexion }
        calib("standing_ankle_angle") { it.standingAnkleAngle }
        calib("standing_hip_y") { it.standingHipY }
        calib("standing_heel_y") { it.standingHeelY }
        // 뒤꿈치 들림의 실제 기준선. `standing_heel_y`는 더는 판정에 쓰지 않는다.
        calib("standing_left_heel_lift") { it.standingLeftHeelLift }
        calib("standing_right_heel_lift") { it.standingRightHeelLift }
        // 뒤꿈치 판정 게이트의 입력. 낮으면 뒤꿈치값이 노이즈에 묻힌다.
        calib("foot_tibia_ratio") { it.footTibiaRatio }
        calib("calibration_frames") { it.framesUsed }
        calib("calibration_jitter") { it.jitter }

        add(Column("frame_count") { it.frames.size })
        add(Column("rep_count") { it.reps.size })
        add(Column("reference_rep_count") { it.referenceReps.size })
    }

    // ── frames ──────────────────────────────────────────────

    private val FRAME_COLUMNS: List<Column<CaptureFrame>> = buildList {
        add(Column("frame_index") { it.frameIndex })
        add(Column("timestamp_ms") { it.timestampMs })
        add(Column("rep_id") { it.repId })
        add(Column("phase") { it.phase.name })
        // 시간이 아니라 무릎 굴곡 기준 진행도다 (문서 §15.1).
        add(Column("phase_progress") { it.phaseProgress })

        // 26점 × (원본 x·y, 필터 x·y, score).
        // 원본을 남기는 이유: 필터나 특징 수식을 바꾼 뒤 전체 데이터를 다시 계산할 수
        // 있어야 한다. 필터 후 값만 남기면 스무딩을 바꿀 때마다 재촬영이다.
        KeypointType.entries.forEach { type ->
            val index = type.index
            add(Column("kp${index}_raw_x") { it.rawKeypoints[index].x })
            add(Column("kp${index}_raw_y") { it.rawKeypoints[index].y })
            add(Column("kp${index}_x") { it.filteredKeypoints[index].x })
            add(Column("kp${index}_y") { it.filteredKeypoints[index].y })
            add(Column("kp${index}_score") { it.filteredKeypoints[index].confidence })
        }

        fun feature(name: String, extract: (FrameFeatures) -> Any?) =
            add(Column<CaptureFrame>(name) { extract(it.features) })

        feature("left_knee_flexion") { it.leftKneeFlexion }
        feature("right_knee_flexion") { it.rightKneeFlexion }
        feature("left_hip_flexion") { it.leftHipFlexion }
        feature("right_hip_flexion") { it.rightHipFlexion }
        feature("left_ankle_dorsi") { it.leftAnkleDorsiflexion }
        feature("right_ankle_dorsi") { it.rightAnkleDorsiflexion }
        feature("depth_ratio") { it.depthRatio }
        feature("thigh_angle") { it.thighAngle }
        feature("trunk_lean") { it.trunkLean }
        feature("shin_angle") { it.shinAngle }
        feature("hip_drop_ratio") { it.hipDropRatio }
        feature("hip_travel_ratio") { it.hipTravelRatio }
        // 판정에 쓰는 값(활성측)과 좌우 원값을 함께 낸다 — 임계값을 다시 놓을 때
        // 판정이 실제로 본 값이 무엇인지 알아야 한다.
        feature("heel_rise") { it.heelRise }
        feature("left_heel_rise") { it.leftHeelRise }
        feature("right_heel_rise") { it.rightHeelRise }
        feature("stance_width_ratio") { it.stanceWidthRatio }
        feature("foot_tibia_ratio") { it.footTibiaRatio }
        feature("left_medial_knee") { it.leftMedialKneeDisplacement }
        feature("right_medial_knee") { it.rightMedialKneeDisplacement }
        // 무릎–발끝 정렬. 0이 정답이고 양쪽 다 오류다 — 힙–발목 기준선 방식과 달리
        // 기준점이 정의에서 나온다.
        feature("left_knee_toe") { it.leftKneeToeDeviation }
        feature("right_knee_toe") { it.rightKneeToeDeviation }
        // 무릎–발끝 판정의 게이트. 무릎 각도로 게이트하면 무릎을 모으는 오류가 게이트를
        // 닫아버리므로(실측 상관 −0.586) 세로 거리만 쓰는 이 값으로 판단한다.
        feature("shin_depth_ratio") { it.shinDepthRatio }
        feature("hip_shift_ratio") { it.hipShiftRatio }
        feature("pelvis_tilt") { it.pelvisTilt }
        feature("shoulder_tilt") { it.shoulderTilt }
        feature("trunk_lateral_lean") { it.trunkLateralLean }
        feature("left_foot_rotation") { it.leftFootRotation }
        feature("right_foot_rotation") { it.rightFootRotation }
        feature("knee_asymmetry") { it.kneeFlexionAsymmetry }
        feature("min_confidence") { it.minConfidence }
    }

    // ── reps ────────────────────────────────────────────────

    private val REP_COLUMNS: List<Column<CaptureRep>> = buildList {
        add(Column("rep_id") { it.repId })
        // 카운팅과 자세 평가를 분리한다 (문서 §2.3). 발이 가려져 자세를 못 봐도
        // 횟수는 셀 수 있다.
        add(Column("counted") { if (it.counted) 1 else 0 })
        add(Column("form_evaluable") { if (it.formEvaluable) 1 else 0 })
        add(Column("start_ms") { it.startMs })
        add(Column("bottom_ms") { it.bottomMs })
        add(Column("end_ms") { it.endMs })
        add(Column("duration_ms") { it.durationMs })
        add(Column("descent_duration_ms") { it.descentDurationMs })
        add(Column("ascent_duration_ms") { it.ascentDurationMs })
        add(Column("max_knee_flexion") { it.maxKneeFlexion })
        add(Column("max_hip_flexion") { it.maxHipFlexion })
        add(Column("max_ankle_dorsi") { it.maxAnkleDorsiflexion })
        add(Column("max_depth_ratio") { it.maxDepthRatio })
        add(Column("min_thigh_angle") { it.minThighAngle })
        add(Column("max_trunk_lean") { it.maxTrunkLean })
        add(Column("max_heel_rise") { it.maxHeelRise })
        add(Column("max_left_medial_knee") { it.maxLeftMedialKnee })
        add(Column("max_right_medial_knee") { it.maxRightMedialKnee })
        add(Column("max_hip_shift") { it.maxHipShift })
        add(Column("valid_frame_ratio") { it.validFrameRatio })
        add(Column("frame_count") { it.frameCount })
        // 샘플링 조밀도. 세션 단위 analysis_fps 하나로는 발열 스로틀링에 따른 세션 내
        // 저하를 볼 수 없다 (실측 26.8 → 19.2 fps인데 세션 컬럼은 27.9였다).
        add(Column("frame_interval_ms") { it.frameIntervalMs })
        add(Column("max_frame_interval_ms") { it.maxFrameIntervalMs })
        // 이 값이 max_frame_interval_ms의 몇 배인지가 "극값을 놓쳤을 수 있는가"의 답이다.
        add(Column("bottom_dwell_ms") { it.bottomDwellMs })
        // 세션 촬영 의도. rep마다 사람이 고른 값이 아니라 세션 단위 선언이다.
        add(Column("intent") { it.intent.name })
        // 같은 세션 다른 rep 대비 최대 편차(MAD 단위). 자동 판정의 근거이므로 함께 남긴다 —
        // 경계를 나중에 옮기려면 경계 근처 값들이 보여야 한다.
        add(Column("deviation_mad") { it.deviation })
        add(Column("is_outlier") { if (RepOutliers.isOutlier(it.deviation)) 1 else 0 })
        // 사람이 확인해 덮어쓴 경우만 값이 있다. 비어 있으면 자동 판정을 따랐다는 뜻이다.
        add(Column("manual_include") { it.manualInclude?.let { v -> if (v) 1 else 0 } })
        add(Column("include_in_reference") { if (it.includeInReference) 1 else 0 })
        // 제외된 rep도 삭제하지 않고 이유를 보관한다 (문서 §14.4).
        add(Column("exclusion_reason") { it.exclusionReason?.name })
    }

    // ── 공개 API ────────────────────────────────────────────

    val SESSION_FIELDS: List<String> get() = SESSION_COLUMNS.map { it.name }
    val FRAME_FIELDS: List<String> get() = FRAME_COLUMNS.map { it.name }
    val REP_FIELDS: List<String> get() = REP_COLUMNS.map { it.name }

    fun sessionCsvHeader(): String = SESSION_FIELDS.joinToString(",")
    fun sessionCsvRow(record: CaptureSessionRecord): String =
        SESSION_COLUMNS.joinToString(",") { csv(it.extract(record)) }

    /** 프레임 CSV는 `session_id`를 앞에 붙여 조인 가능하게 한다. */
    fun frameCsvHeader(): String = ("session_id," + FRAME_FIELDS.joinToString(","))
    fun frameCsvRow(sessionId: String, frame: CaptureFrame): String =
        csv(sessionId) + "," + FRAME_COLUMNS.joinToString(",") { csv(it.extract(frame)) }

    fun repCsvHeader(): String = ("session_id," + REP_FIELDS.joinToString(","))
    fun repCsvRow(sessionId: String, rep: CaptureRep): String =
        csv(sessionId) + "," + REP_COLUMNS.joinToString(",") { csv(it.extract(rep)) }

    fun frameJsonLine(sessionId: String, frame: CaptureFrame): String =
        json(listOf("session_id" to sessionId) + FRAME_COLUMNS.map { it.name to it.extract(frame) })

    fun repJsonLine(sessionId: String, rep: CaptureRep): String =
        json(listOf("session_id" to sessionId) + REP_COLUMNS.map { it.name to it.extract(rep) })

    fun sessionJsonLine(record: CaptureSessionRecord): String =
        json(SESSION_COLUMNS.map { it.name to it.extract(record) })

    // ── 직렬화 ──────────────────────────────────────────────

    /**
     * 소수는 고정 소수점으로 쓴다.
     *
     * `toString()`은 작은 값에 `1.0E-5` 같은 지수 표기를 내는데 CSV 파서마다 해석이
     * 갈리고 스프레드시트에서는 문자열로 들어간다.
     */
    private fun number(value: Number): String = when (value) {
        is Float -> String.format(java.util.Locale.US, "%.6f", value)
        is Double -> String.format(java.util.Locale.US, "%.6f", value)
        else -> value.toString()
    }

    private fun csv(value: Any?): String = when (value) {
        // 빈 문자열로 둔다. "null"이라고 쓰면 pandas가 그 컬럼 전체를 문자열로 읽어
        // 숫자 연산이 깨진다.
        null -> ""
        is Number -> number(value)
        else -> value.toString().let { text ->
            if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + text.replace("\"", "\"\"") + "\""
            } else {
                text
            }
        }
    }

    private fun json(entries: List<Pair<String, Any?>>): String =
        entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
            "${jsonString(key)}:${jsonValue(value)}"
        }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number -> number(value)
        else -> jsonString(value.toString())
    }

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
