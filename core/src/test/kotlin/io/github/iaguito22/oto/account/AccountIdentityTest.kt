package io.github.iaguito22.oto.account

import org.junit.Assert.*
import org.junit.Test

class AccountIdentityTest {
    @Test fun onlyRecognizesAConfirmedMatchingAccount() {
        val original = Session("cookie-a", "visitor", "account-one", "0")
        assertTrue(sameKnownAccount(original, original.copy(cookie="cookie-b")))
        assertFalse(sameKnownAccount(original, original.copy(dataSyncId="account-two")))
        assertFalse(sameKnownAccount(original, original.copy(authUser="1")))
        assertFalse(sameKnownAccount(original.copy(dataSyncId=""), original.copy(dataSyncId="")))
        assertFalse(sameKnownAccount(null, original))
    }
}
