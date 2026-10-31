package dev.multiprompt.companion.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.update.UpdateRelease
import dev.multiprompt.companion.update.UpdateState
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInGateTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(scheduler))

    private val models = ViewModelStore()
    private lateinit var model: MainViewModel
    private lateinit var state: MutableStateFlow<AppUiState>
    private lateinit var updates: MutableStateFlow<UpdateState>

    @After
    fun closeModels() {
        compose.runOnUiThread { models.clear() }
    }

    private fun render(signedIn: Boolean = false) {
        compose.runOnUiThread {
            val application = MultipromptApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(
                application, "attach",
                ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()),
            )
            application.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).edit().clear().commit()
            application.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putLong("last_check_ms", System.currentTimeMillis()).commit()
            model = MainViewModel(application).also { models.put("main", it) }
            assertFalse(model.syncAuth.hasSession())
            assertFalse(model.state.value.signedIn)
            state = ReflectionHelpers.getField(model, "_state")
            val host = HostProfile("host", "VPS", "example.com", username = "user", keySecretId = "missing")
            state.value = state.value.copy(
                signedIn = signedIn,
                hosts = listOf(host),
                sessions = listOf(TmuxSession(host.id, "Private session", 1, 0, 0)),
            )
            updates = ReflectionHelpers.getField(model.updates, "_state")
            updates.value = UpdateState.Checking
        }
        compose.setContent {
            MultipromptTheme { MultipromptApp(model) }
        }
    }

    @Test
    fun signedOutShowsOnlySignIn() {
        render()
        compose.onNodeWithText("Multi Bot").assertIsDisplayed()
        compose.onNodeWithText("Sign in to see your servers, workspaces and sessions.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("app-version").assertIsDisplayed()
        compose.onNodeWithText("Inbox").assertDoesNotExist()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Frequent").assertDoesNotExist()
        compose.onNodeWithText("Private session").assertDoesNotExist()
        compose.onNodeWithContentDescription("Session list actions").assertDoesNotExist()
        compose.onNodeWithText("Signed out. Sign in to see your workspaces and splits.").assertDoesNotExist()
        captureSignIn()
        compose.runOnIdle { state.value = state.value.copy(accountError = "Sign-in failed; try again") }
        compose.onNodeWithText("Sign-in failed; try again").assertIsDisplayed()
    }

    @Test
    fun signedInShowsApp() {
        render(signedIn = true)
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        compose.onNodeWithText("All").assertIsDisplayed().performClick()
        compose.onNodeWithText("Private session").assertIsDisplayed()
        compose.onNodeWithContentDescription("Session list actions").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Sign in to see your servers, workspaces and sessions.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(signedIn = false) }
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("Private session").assertDoesNotExist()
    }

    @Test
    fun signedOutCanStartUpdateInstall() {
        render()
        val release = UpdateRelease(Long.MAX_VALUE, "next", "https://example.com/app.apk", "0".repeat(64), 1, "")
        compose.runOnIdle { updates.value = UpdateState.Available(release) }
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithText("Install").assertIsDisplayed().performClick()
        compose.onNodeWithText("Allow installs to update to vnext").assertIsDisplayed()
        compose.runOnIdle { assertEquals(UpdateState.PermissionRequired(release), updates.value) }
        compose.onNodeWithText("Inbox").assertDoesNotExist()
    }

    private fun captureSignIn() {
        val directory = System.getProperty("m3.shots") ?: return
        val root = compose.onRoot()
        val view = (checkNotNull(root.fetchSemanticsNode().root) as ViewRootForTest).view.rootView
        val attachInfo = ReflectionHelpers.getField<Any>(view, "mAttachInfo")
        val accelerated = view.isHardwareAccelerated
        ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", false)
        val redraw = CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
            delay(16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            view.viewTreeObserver.dispatchOnDraw()
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = try {
            root.captureToImage().asAndroidBitmap()
        } finally {
            redraw.cancel()
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", accelerated)
        }
        assertEquals(390, bitmap.width)
        val output = File(directory, "signin.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
