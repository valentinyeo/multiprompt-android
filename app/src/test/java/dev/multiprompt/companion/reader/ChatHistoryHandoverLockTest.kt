package dev.multiprompt.companion.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryHandoverLockTest {
  @Test fun handoverWaitsForThePreviousOwnerBeforePublishingTerminalOwnership() {
    val source = sequenceOf(File("src/main/assets/chat_history_reader.py"),
      File("app/src/main/assets/chat_history_reader.py")).first { it.isFile }.readText()
    val handover = Regex("""def host_handover\(provider, sid\):.*?(?=\n\ndef )""", RegexOption.DOT_MATCHES_ALL)
      .find(source)?.value ?: error("host_handover missing")
    assertTrue(handover.contains("handle = host_lock(provider, sid, wait=True)"))
    assertTrue(handover.contains("host_release(handle)"))
  }
}