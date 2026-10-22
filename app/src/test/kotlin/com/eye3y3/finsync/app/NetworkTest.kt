package com.eye3y3.finsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    @Test fun homeAndVpnAddressesAreFine() {
        for (a in listOf("http://192.168.1.10:8096", "192.168.1.10:8096", "http://10.0.0.5", "http://172.20.1.1:8096", "http://100.101.102.103:8096",
            "http://jellyfin:8096", "jellyfin:8096", "http://nas.local:8096", "nas.local", "http://media.home.arpa", "http://box.tail1234.ts.net",
            "http://localhost:8096", "http://[fd00::1]:8096", "https://music.example.com", "music.example.com".let { "https://$it" }, "http://", ""))
            assertFalse(a, plainHttpOutsideHome(a))
    }

    @Test fun plainHttpOnTheInternetIsFlagged() {
        for (a in listOf("http://music.example.com", "music.example.com", "jellyfin.example.com:8096", "http://203.0.113.7:8096", "203.0.113.7",
            "HTTP://jf.duckdns.org", "http://[2001:db8::1]", "http://192.168.1.1.evil.com", "http://10.0.0.1.attacker.tld",
            "http://134744072", "http://my_server.example.com", "http://999.1.1.1", "http://10.0.0.1@evil.com"))
            assertTrue(a, plainHttpOutsideHome(a))
    }
}
