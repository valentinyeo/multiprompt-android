package dev.multiprompt.companion.computer

import android.content.Context
import android.graphics.Bitmap
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.TmuxSession
import java.io.File

class ComputerStore(context: Context) {
    private val preferences = context.getSharedPreferences("computer_view", Context.MODE_PRIVATE)
    private val pictures = File(context.filesDir, "computer")

    fun target(hostId: String): ComputerTarget =
        ComputerTarget.parse(preferences.getString(TARGET_PREFIX + hostId, null)) ?: ComputerTarget.Default

    fun setTarget(hostId: String, target: ComputerTarget) {
        preferences.edit().putString(TARGET_PREFIX + hostId, target.value).apply()
    }

    fun showsComputer(session: TmuxSession): Boolean =
        preferences.getBoolean(SHOWN_PREFIX + SessionReadStore.key(session.hostId, session.name), false)

    fun setShowsComputer(session: TmuxSession, value: Boolean) {
        preferences.edit().putBoolean(SHOWN_PREFIX + SessionReadStore.key(session.hostId, session.name), value).apply()
    }

    fun lastPicture(session: TmuxSession): File? =
        pictureFile(session).takeIf { it.isFile && it.length() > 0 }

    fun saveLastPicture(session: TmuxSession, picture: Bitmap) {
        runCatching {
            pictures.mkdirs()
            pictureFile(session).outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    fun clearLastPicture(session: TmuxSession) {
        runCatching { pictureFile(session).delete() }
    }

    private fun pictureFile(session: TmuxSession): File =
        File(pictures, SessionReadStore.key(session.hostId, session.name).replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png")

    private companion object {
        const val TARGET_PREFIX = "target::"
        const val SHOWN_PREFIX = "shown::"
    }
}
