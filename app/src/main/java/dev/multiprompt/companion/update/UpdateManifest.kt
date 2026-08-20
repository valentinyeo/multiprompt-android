package dev.multiprompt.companion.update

import org.json.JSONObject

/** One published build, kept so the Update screen can show a running changelog. */
data class ReleaseNote(
    val versionName: String,
    val date: String,
    val notes: String,
)

data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val history: List<ReleaseNote> = emptyList(),
) {
    companion object {
        fun parse(raw: String): UpdateRelease {
            val json = JSONObject(raw)
            require(json.getInt("schemaVersion") == 1) { "Unsupported update manifest" }
            val release = UpdateRelease(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").lowercase(),
                sizeBytes = json.getLong("sizeBytes"),
                notes = json.optString("notes"),
                history = json.optJSONArray("history")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let { entry ->
                            ReleaseNote(
                                versionName = entry.optString("versionName"),
                                date = entry.optString("date"),
                                notes = entry.optString("notes"),
                            )
                        }
                    }.filter { it.versionName.isNotBlank() }
                }.orEmpty(),
            )
            require(release.versionCode > 0) { "Invalid version code" }
            require(release.versionName.isNotBlank()) { "Invalid version name" }
            require(release.apkUrl.startsWith("https://")) { "Updates must use HTTPS" }
            require(release.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK checksum" }
            require(release.sizeBytes in 1..MAX_APK_BYTES) { "Invalid APK size" }
            return release
        }

        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Current(
        val versionName: String,
        val notes: String = "",
        val history: List<ReleaseNote> = emptyList(),
    ) : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class PermissionRequired(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String, val release: UpdateRelease? = null) : UpdateState
}

