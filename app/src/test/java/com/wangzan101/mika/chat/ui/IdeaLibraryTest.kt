package com.wangzan101.mika.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class IdeaLibraryTest {
    @Test fun chineseUiUsesTheDomesticSetInItsScript() {
        assertEquals("zh" to "zh-Hans", IdeaLibrary.setFor(Locale.forLanguageTag("zh-Hans")))
        assertEquals("zh" to "zh-Hans", IdeaLibrary.setFor(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh" to "zh-Hant", IdeaLibrary.setFor(Locale.forLanguageTag("zh-Hant")))
        assertEquals("zh" to "zh-Hant", IdeaLibrary.setFor(Locale.forLanguageTag("zh-TW")))
    }

    @Test fun otherLanguagesUseTheOverseasSetInTheirOwnLanguage() {
        assertEquals("intl" to "en", IdeaLibrary.setFor(Locale.forLanguageTag("en-US")))
        assertEquals("intl" to "ja", IdeaLibrary.setFor(Locale.JAPAN))
        assertEquals("intl" to "id", IdeaLibrary.setFor(Locale("in", "ID")))
    }

    @Test fun rowsAlternateScenesAndSplitEvenly() {
        val items = listOf("a1", "a2", "a3", "b1", "b2", "c1")
        val rows = IdeaLibrary.arrange(items, { it.take(1) }, Random(1))
        assertEquals(2, rows.size)
        assertEquals(items.toSet(), rows.flatten().toSet())
        assertTrue(rows[0].size - rows[1].size in 0..1)
        // 交错之前同一场景不挨着:前三条来自三个不同场景
        val woven = rows[0].zip(rows[1]).flatMap { listOf(it.first, it.second) } + rows[0].drop(rows[1].size)
        assertEquals(3, woven.take(3).map { it.take(1) }.toSet().size)
    }

    @Test fun differentOpensShuffleDifferently() {
        val items = (1..20).map { "s${it % 4}-$it" }
        assertNotEquals(
            IdeaLibrary.arrange(items, { it.take(2) }, Random(1)),
            IdeaLibrary.arrange(items, { it.take(2) }, Random(2)),
        )
    }
}
