package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFindTest {
    @Test
    fun foldsCaseInsideOneMessage() {
        val hits = ChatFind.find(listOf("Hello Straße"), "hello straße")
        assertEquals(listOf(ChatFind.Hit(0, 0, 12)), hits)
        assertEquals(ChatFind.find(listOf("HELLO"), "hello"), ChatFind.find(listOf("hello"), "HELLO"))
    }

    @Test
    fun findsOverlappingAndRepeatedMatches() {
        assertEquals(
            listOf(ChatFind.Hit(0, 0, 2), ChatFind.Hit(0, 1, 3)),
            ChatFind.find(listOf("aaa"), "aa"),
        )
        assertEquals(
            listOf(ChatFind.Hit(0, 0, 2), ChatFind.Hit(0, 2, 4), ChatFind.Hit(0, 4, 6)),
            ChatFind.find(listOf("hahaha"), "ha"),
        )
    }

    @Test
    fun emptyQueryMatchesNothing() {
        assertTrue(ChatFind.find(listOf("hello"), "").isEmpty())
        assertTrue(ChatFind.find(listOf("hello"), "   ").isEmpty())
        assertTrue(ChatFind.find(emptyList(), "hello").isEmpty())
    }

    @Test
    fun keepsMatchOrderAcrossMessages() {
        val hits = ChatFind.find(listOf("b ca", "cat", "CA"), "ca")
        assertEquals(
            listOf(
                ChatFind.Hit(0, 2, 4),
                ChatFind.Hit(1, 0, 2),
                ChatFind.Hit(2, 0, 2),
            ),
            hits,
        )
        assertTrue(ChatFind.find(listOf("ab", "cd"), "bc").isEmpty())
    }

    @Test
    fun stepsWrapAndTheCountUsesOneBasedIndex() {
        assertEquals(0, ChatFind.step(2, 3, previous = false))
        assertEquals(2, ChatFind.step(0, 3, previous = true))
        assertEquals(1, ChatFind.step(0, 3, previous = false))
        assertEquals(0, ChatFind.step(0, 0, previous = true))
        assertEquals("3 of 3", ChatFind.label(2, 3))
        assertEquals("0 of 0", ChatFind.label(0, 0))
        assertEquals("4 of 4", ChatFind.label(Int.MAX_VALUE, 4))
    }

    @Test
    fun assistantSearchUsesTheVisibleWords() {
        val shown = ChatFind.display("assistant", "See **Cat** now")
        assertEquals("See Cat now", shown)
        assertEquals(listOf(ChatFind.Hit(0, 4, 7)), ChatFind.find(listOf(shown), "cat"))
        assertEquals("**Cat**", ChatFind.display("user", "**Cat**"))
    }

    @Test
    fun visibleTextJoinsBlocksInOrder() {
        val source = "Hello **bold**\n\n- item\n\n```\ncode\n```"
        val blocks = MarkdownParser.parse(source)
        assertEquals(
            blocks.joinToString("\n") { MarkdownParser.blockText(it) },
            MarkdownParser.visibleText(source),
        )
        assertTrue(MarkdownParser.visibleText(source).contains("bold"))
        assertTrue(MarkdownParser.visibleText(source).contains("code"))
        assertTrue(!MarkdownParser.visibleText(source).contains("**"))
    }

    @Test
    fun skillPillTakesTheStrongestOverlappingHit() {
        val other = ChatFind.Hit(0, 0, 4)
        val current = ChatFind.Hit(0, 2, 6)
        assertEquals(SkillHitPaint.None, skillHitPaint(0, 4, 0, emptyList(), null))
        assertEquals(SkillHitPaint.None, skillHitPaint(0, 4, 10, listOf(other), null))
        assertEquals(SkillHitPaint.Other, skillHitPaint(0, 4, 0, listOf(other), ChatFind.Hit(1, 0, 4)))
        assertEquals(SkillHitPaint.Current, skillHitPaint(0, 4, 0, listOf(other, current), current))
        assertEquals(SkillHitPaint.Current, skillHitPaint(1, 3, 1, listOf(current), current))
    }
}
