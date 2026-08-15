package com.github.andreyasadchy.xtra.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataCacheKeyTest {

    @Test
    fun `account identity keeps id and login aliases deterministic`() {
        assertEquals(
            listOf("id:123", "login:somebody"),
            MetadataCache.identityKeys("id", "login", " 123 ", "SomeBody"),
        )
    }

    @Test
    fun `game identity prefers stable id but retains slug and name aliases`() {
        assertEquals(
            listOf("id:42", "slug:some-game", "name:some game"),
            MetadataCache.gameIdentityKeys("42", "Some-Game", "Some Game"),
        )
    }

    @Test
    fun `blank identity values are not cached`() {
        assertEquals(
            emptyList<String>(),
            MetadataCache.identityKeys("id", "login", " ", null),
        )
    }
}
