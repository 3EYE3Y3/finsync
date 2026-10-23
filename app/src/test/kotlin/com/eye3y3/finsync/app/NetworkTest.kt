package com.eye3y3.finsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    @Test fun allHttpAddressesNeedConfirmation() {
        for (a in listOf("http://192.168.1.10:8096", "http://10.0.0.5", "http://172.20.1.1:8096", "http://100.101.102.103:8096",
            "http://jellyfin:8096", "http://nas.local:8096", "http://media.home.arpa", "http://box.tail1234.ts.net",
            "http://localhost:8096", "http://[fd00::1]:8096", "http://[::1]", "HTTP://music.example.com")) {
            assertTrue(a, needsHttpConfirmation(a, false))
            assertFalse(a, needsHttpConfirmation(a, true))
        }
    }

    @Test fun httpsAndSchemeLessAddressesDoNotNeedHttpConfirmation() {
        for (a in listOf("https://music.example.com", "192.168.1.10:8096", "jellyfin:8096", "nas.local", ""))
            assertFalse(a, needsHttpConfirmation(a, false))
    }
}
