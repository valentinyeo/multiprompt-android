package dev.multiprompt.companion.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatchTimeTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()

    @Test
    fun lastBatchIsTheAgentRepliesAfterTheLastUserMessage() {
        assertEquals(2, lastBatchStart(listOf("user", "user", "assistant", "assistant")))
        assertEquals(3, lastBatchStart(listOf("user", "assistant", "user", "assistant", "assistant")))
        assertEquals(1, lastBatchStart(listOf("assistant", "user", "user")))
        assertEquals(0, lastBatchStart(listOf("assistant", "assistant")))
        assertNull(lastBatchStart(emptyList()))
    }

    @Test
    fun labelShowsTodayYesterdayOrTheDate() {
        assertEquals("Today 07:33", batchTimeLabel("2026-11-06T07:33:10Z", now, zone))
        assertEquals("Yesterday 23:05", batchTimeLabel("2026-11-05T23:05:00Z", now, zone))
        assertEquals("3 Nov 07:33", batchTimeLabel("2026-11-03T07:33:00Z", now, zone))
        assertNull(batchTimeLabel(null, now, zone))
        assertNull(batchTimeLabel("", now, zone))
        assertNull(batchTimeLabel("not a time", now, zone))
    }

    @Test
    fun oneCentredTimeLineIsShown() {
        compose.setContent {
            MultipromptTheme(sunlight = false, readerStyle = true) {
                BatchTimeLine("Today 07:33", sunlight = false)
            }
        }
        compose.onAllNodesWithTag("batch-time").assertCountEquals(1)
        compose.onNodeWithTag("batch-time").assertTextEquals("Today 07:33")
    }
}
