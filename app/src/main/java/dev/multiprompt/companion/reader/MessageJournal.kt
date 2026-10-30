package dev.multiprompt.companion.reader

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

class MessageJournal(private val preferences: SharedPreferences) {
    data class Entry(
        val id: String,
        val account: String,
        val scope: MessageProtocol.Scope,
        val operation: String,
        val messageId: String,
        val text: String?,
        val epoch: Long? = null,
        val attempt: String? = null,
        val issued: Boolean = false,
    )

    @Synchronized
    fun create(account: String, scope: MessageProtocol.Scope, operation: String, messageId: String? = null, text: String? = null, epoch: Long? = null, attempt: String? = null): Entry {
        require(account.isNotBlank())
        require(operation in setOf("submit", "retry", "dismiss", "reset"))
        require(operation != "submit" || (!text.isNullOrBlank() && '\u0000' !in text))
        require(operation == "submit" || text == null)
        val id = UUID.randomUUID().toString()
        val target = if (operation == "submit") id else messageId.orEmpty()
        require(operation == "reset" || target.matches(Regex("[A-Za-z0-9_-]{16,80}")))
        if (operation == "retry" || operation == "dismiss") require(epoch != null && !attempt.isNullOrBlank())
        val entry = Entry(id, account, scope, operation, target, text, epoch, attempt)
        val value = JSONObject().put("account", account).put("provider", scope.provider).put("sid", scope.sid)
            .put("tmux", scope.tmux).put("operation", operation).put("message_id", target)
            .put("text", text ?: JSONObject.NULL)
            .put("epoch", epoch ?: JSONObject.NULL).put("attempt", attempt ?: JSONObject.NULL).put("issued", false)
        check(preferences.edit().putString(id, value.toString()).commit())
        return entry
    }

    @Synchronized
    fun entries(): List<Entry> = preferences.all.map { (id, raw) ->
        require(id.matches(Regex("[A-Za-z0-9_-]{16,80}")))
        val value = JSONObject(raw as String)
        Entry(id, value.getString("account"), MessageProtocol.Scope(value.getString("provider"), value.getString("sid"), value.getString("tmux")),
            value.getString("operation"), value.getString("message_id"), if (value.isNull("text")) null else value.getString("text"),
            if (value.isNull("epoch")) null else value.getLong("epoch"), if (value.isNull("attempt")) null else value.getString("attempt"),
            if (value.has("issued")) value.getBoolean("issued") else true)
    }

    @Synchronized
    fun markIssued(id: String) {
        val value = JSONObject(requireNotNull(preferences.getString(id, null))).put("issued", true)
        check(preferences.edit().putString(id, value.toString()).commit())
    }

    @Synchronized
    fun remove(id: String) {
        check(preferences.edit().remove(id).commit())
    }
}
