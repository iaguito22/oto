package io.github.iaguito22.oto.account

import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCreationGuardTest {
    @Test fun blocksAmbiguousOrInterruptedCreation() {
        assertTrue(runCatching { ensureSafePlaylistCreation("one", "Riot", setOf("one"), emptyList()) }.isFailure)
        assertTrue(runCatching { ensureSafePlaylistCreation("one", "Riot", emptySet(), listOf(" RIOT ")) }.isFailure)
        assertTrue(runCatching { ensureSafePlaylistCreation("one", "Riot", emptySet(), listOf("Otro")) }.isSuccess)
    }
}
