package com.mist.formchecker.data.local

import androidx.room.TypeConverter
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.RepPhase
import kotlinx.serialization.json.Json

/** Room이 enum을 문자열로 저장하게 한다. ordinal을 쓰면 enum 순서를 바꿀 때 과거 행이 깨진다. */
class Converters {
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name
}

/**
 * `error_flags` jsonb 직렬화.
 *
 * ## 왜 boolean 컬럼 5개가 아닌가
 * 설계문서 4장이 `jsonb`로 정의했고, 경고 종류는 실제로 계속 늘고 있다(초판 2종 → 현재
 * 5종, 뒤꿈치 들림·좌우 비대칭이 대기 중). 컬럼으로 두면 종류가 늘 때마다 Room
 * 마이그레이션과 Supabase ALTER가 함께 필요하다.
 *
 * ## 판정하지 않은 항목은 키를 넣지 않는다
 * `false`와 "판정 불가"가 다르다. 정면 rep에 `shallow_depth: false`가 들어가면 "깊이는
 * 괜찮았다"로 읽히는데 실제로는 재지도 않았다. 그래서 그 각도에서 **판정한 항목만** 키로
 * 넣는다 (설계문서 4.2절).
 */
object ErrorFlags {

    private val json = Json { encodeDefaults = true }

    /** `FormWarning.SHALLOW_DEPTH` → `"shallow_depth"`. 서버 컬럼과 같은 표기다. */
    fun keyOf(warning: FormWarning): String = warning.name.lowercase()

    fun encode(flags: Map<FormWarning, Boolean>): String =
        json.encodeToString(flags.mapKeys { keyOf(it.key) })

    fun decode(raw: String): Map<String, Boolean> = runCatching {
        json.decodeFromString<Map<String, Boolean>>(raw)
    }.getOrDefault(emptyMap())

    /**
     * 경고별 구간을 `{"knee_flared":["DESCENDING","BOTTOM"]}` 형태로 담는다.
     *
     * `error_flags`와 같은 키 표기를 쓴다 — 두 컬럼이 같은 경고를 다른 이름으로 부르면
     * 서버에서 조인할 때 매핑 표가 하나 더 필요해진다.
     */
    fun encodePhases(phases: Map<FormWarning, Set<RepPhase>>): String =
        json.encodeToString(
            phases.entries.associate { (w, p) -> keyOf(w) to p.map { it.name }.sorted() },
        )

    /** 저장된 구간을 되돌린다. 읽을 수 없으면 빈 맵 — 구간을 말하지 않게 된다. */
    fun decodePhases(raw: String?): Map<FormWarning, Set<RepPhase>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val decoded = runCatching {
            json.decodeFromString<Map<String, List<String>>>(raw)
        }.getOrDefault(emptyMap())
        return FormWarning.entries.mapNotNull { warning ->
            decoded[keyOf(warning)]
                ?.mapNotNull { name -> runCatching { RepPhase.valueOf(name) }.getOrNull() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?.let { warning to it }
        }.toMap()
    }

    /** 경고가 난 종류만. 화면에 나열할 때 쓴다. */
    fun warned(raw: String): List<FormWarning> {
        val decoded = decode(raw)
        return FormWarning.entries.filter { decoded[keyOf(it)] == true }
    }
}
