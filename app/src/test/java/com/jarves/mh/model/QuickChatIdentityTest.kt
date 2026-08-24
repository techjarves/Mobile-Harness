package com.jarves.mh.model

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickChatIdentityTest {
    @Test
    fun generatedIdentityIsReadableAndUnique() {
        val first = generateQuickChatIdentity(emptySet(), Random(7))
        val second = generateQuickChatIdentity(setOf(first.slug), Random(7))

        assertTrue(first.slug.matches(Regex("[a-z]+-[a-z]+")))
        assertTrue(first.displayName.contains(' '))
        assertNotEquals(first.slug, second.slug)
    }

    @Test
    fun generatedNamesAvoidLivingCelebrityExamples() {
        repeat(100) { seed ->
            val identity = generateQuickChatIdentity(emptySet(), Random(seed))
            assertFalse(identity.slug.contains("elon"))
            assertFalse(identity.slug.contains("jobs"))
        }
    }
}
