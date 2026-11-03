package dev.multiprompt.companion.account

import org.json.JSONObject
import java.io.File

object BotPhotoKey {
    fun hex(host: Int, session: String): String = ""
}

class BotPhotoCache(private val dir: File) {
    fun file(tab: AccountTab): File? = null

    suspend fun sync(tabs: List<AccountTab>, fetch: suspend (String) -> ByteArray?): Boolean = false

    companion object {
        fun validPng(bytes: ByteArray): Boolean = false
        fun decode(reply: JSONObject): ByteArray? = null
    }
}
