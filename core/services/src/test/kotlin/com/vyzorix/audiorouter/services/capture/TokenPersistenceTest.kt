package com.vyzorix.audiorouter.services.capture

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TokenPersistenceTest {

    @Test
    fun `persist then read returns the same metadata`() = runTest {
        val persistence = TokenPersistence(tokenEncryptor = fakeTokenEncryptor())
        val original = PersistedProjectionMetadata(
            grantedAtEpochMs = 1_000L,
            sampleRateHz = 48_000,
            channelCount = 1,
            triggerOrigin = "bootstrap",
        )
        persistence.persist(original)
        val readBack = persistence.read()
        assertNotNull(readBack)
        assertEquals(original.grantedAtEpochMs, readBack.grantedAtEpochMs)
        assertEquals(original.sampleRateHz, readBack.sampleRateHz)
        assertEquals(original.channelCount, readBack.channelCount)
        assertEquals(original.triggerOrigin, readBack.triggerOrigin)
    }

    @Test
    fun `read with no prior persist returns null`() = runTest {
        val persistence = TokenPersistence(tokenEncryptor = fakeTokenEncryptor())
        assertNull(persistence.read())
    }

    @Test
    fun `clear erases the persisted entry`() = runTest {
        val persistence = TokenPersistence(tokenEncryptor = fakeTokenEncryptor())
        persistence.persist(
            PersistedProjectionMetadata(
                grantedAtEpochMs = 1_000L,
                sampleRateHz = 48_000,
                channelCount = 1,
                triggerOrigin = "bootstrap",
            ),
        )
        assertNotNull(persistence.read())
        persistence.clear()
        assertNull(persistence.read())
    }
}
