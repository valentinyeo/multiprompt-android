package dev.multiprompt.companion.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalKeysTest {
    @Test fun specialKeysSendTheShellSequences() {
        val expected = mapOf(
            TerminalKeys.Key.Esc to byteArrayOf(0x1b),
            TerminalKeys.Key.Tab to byteArrayOf(0x09),
            TerminalKeys.Key.Up to byteArrayOf(0x1b, 0x5b, 0x41),
            TerminalKeys.Key.Down to byteArrayOf(0x1b, 0x5b, 0x42),
            TerminalKeys.Key.Left to byteArrayOf(0x1b, 0x5b, 0x44),
            TerminalKeys.Key.Right to byteArrayOf(0x1b, 0x5b, 0x43),
        )
        TerminalKeys.Key.entries.forEach { key ->
            assertArrayEquals(key.label, expected.getValue(key), key.bytes())
        }
    }

    @Test fun ctrlLettersSendControlBytes() {
        mapOf('c' to 0x03, 'd' to 0x04, 'l' to 0x0c).forEach { (letter, code) ->
            assertArrayEquals(byteArrayOf(code.toByte()), TerminalKeys.character(letter.code, ctrl = true))
            assertArrayEquals(byteArrayOf(code.toByte()), TerminalKeys.character(letter.uppercaseChar().code, ctrl = true))
        }
    }

    @Test fun altPrefixesEscapeAndCanCombineWithCtrl() {
        assertArrayEquals(byteArrayOf(0x1b, 0x78), TerminalKeys.character('x'.code, alt = true))
        assertArrayEquals(byteArrayOf(0x1b, 0x03), TerminalKeys.character('c'.code, ctrl = true, alt = true))
    }

    @Test fun unmodifiedTextKeepsUtf8() {
        assertArrayEquals("x".toByteArray(), TerminalKeys.character('x'.code))
        assertArrayEquals("é".toByteArray(), TerminalKeys.character('é'.code))
        assertArrayEquals("😀".toByteArray(), TerminalKeys.character(0x1f600))
    }
}
