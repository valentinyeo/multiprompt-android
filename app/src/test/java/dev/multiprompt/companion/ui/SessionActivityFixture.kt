package dev.multiprompt.companion.ui

import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.account.AccountAuth
import dev.multiprompt.companion.account.AccountHttp
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountReply
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.account.PendingLayout
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.viewModelScope
import org.json.JSONObject
import android.content.Context
import android.content.ContextWrapper
import org.robolectric.RuntimeEnvironment

internal class SessionActivityFixture {
    val app = MultipromptApplication().apply {
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(this, RuntimeEnvironment.getApplication())
    }
    val model = MainViewModel(app)
    val host = HostProfile("v", "Hetzner", "localhost", username = "valentin", keySecretId = "missing")
    val session = TmuxSession(host.id, "one", 1, 0, 0)
    val layout = AccountLayout(1, listOf("Other"), listOf(AccountTab("one", "one", 0, "Other"), AccountTab("two", "two", 0, "Other")))
    val state = field<MutableStateFlow<AppUiState>>(model, "_state")
    val foreground = field<MutableStateFlow<Boolean>>(model, "foreground")

    init {
        val auth = AccountAuth("test://callback", object : AccountHttp {
            override fun request(method: String, path: String, token: String?, body: JSONObject?): AccountReply = error("No network expected")
        }, { if (it == AccountAuth.TOKENS) "{\"account_id\":\"test-account\"}" else null }, { _, _ -> })
        model.syncAuth.javaClass.getDeclaredField("auth").apply { isAccessible = true }.set(model.syncAuth, auth)
        model.javaClass.getDeclaredField("accountRequestRunning").apply { isAccessible = true }.set(model, true)
        model.javaClass.getDeclaredField("pendingLayout").apply { isAccessible = true }.set(model, PendingLayout(layout))
        state.value = state.value.copy(hosts = listOf(host), signedIn = true, accountLayout = layout,
            accountHosts = mapOf(0 to host), sessions = listOf(session, session.copy(name = "two")))
    }

    fun close() {
        model.closeTerminal()
        model.closeReader()
        field<LinkedHashMap<String, AutoCloseable>>(model, "warmReaders").values.forEach { it.close() }
        model.viewModelScope.cancel()
        app.deepgramDictation.close()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(instance) as T
    }
}
