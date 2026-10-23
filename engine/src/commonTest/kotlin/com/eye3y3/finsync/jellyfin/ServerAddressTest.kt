package com.eye3y3.finsync.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerAddressTest {
    @Test fun missingSchemeUsesHttpsIncludingLocalServers() {
        for (address in listOf("192.168.1.10:8096", "jellyfin:8096", "nas.local", "[fd00::1]:8096", "music.example.com/jellyfin")) {
            assertEquals("https://$address", normaliseServerUrl(" $address/ "))
            assertFalse(isPlainHttp(normaliseServerUrl(address)))
        }
    }

    @Test fun explicitHttpIsPreservedAndFlagged() {
        assertEquals("http://nas.local:8096/jellyfin", normaliseServerUrl(" HTTP://nas.local:8096/jellyfin/ "))
        assertTrue(isPlainHttp(" HTTP://nas.local:8096 "))
        assertFalse(isPlainHttp("https://nas.local"))
    }

    @Test fun invalidAddressesAreRejected() {
        for (address in listOf("", "http://", "https://", "ftp://nas.local", "https://user:password@nas.local", "https://nas.local?api_key=secret", "https://nas.local/#fragment"))
            assertFailsWith<IllegalArgumentException>(address) { normaliseServerUrl(address) }
    }
}
