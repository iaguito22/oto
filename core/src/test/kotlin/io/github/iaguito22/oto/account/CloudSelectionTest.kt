package io.github.iaguito22.oto.account

import org.junit.Assert.*
import org.junit.Test

class CloudSelectionTest {
    @Test fun missingOrDamagedConsentDoesNotAuthorizeSync() {
        assertNull(CloudSelection.decode(null))
        assertNull(CloudSelection.decode("{}"))
        assertNull(CloudSelection.decode("{broken"))
    }
    @Test fun importAndExportConsentStayIndependent() {
        val choice=CloudSelection(setOf("remote-only"),setOf("local-only"))
        val restored=CloudSelection.decode(choice.encode())!!
        assertEquals(setOf("remote-only"),restored.imports)
        assertEquals(setOf("local-only"),restored.exports)
        assertFalse("remote-only" in restored.exports)
    }
    @Test fun choosingNoListsIsDifferentFromNeverChoosing() {
        val choice=CloudSelection.decode(CloudSelection().encode())
        assertNotNull(choice)
        assertTrue(choice!!.imports.isEmpty() && choice.exports.isEmpty())
    }
}
