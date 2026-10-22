package com.eye3y3.finsync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTest {
    @Test fun homeAndVpnAddressesAreFine() {
        for (a in listOf("http://192.168.1.10:8096", "http://10.0.0.5", "http://172.20.1.1:8096", "http://100.101.102.103:8096",
            "http://jellyfin:8096", "http://nas.local:8096", "http://media.home.arpa", "http://box.tail1234.ts.net", "http://localhost:8096",
            "http://[fd00::1]:8096", "https://music.example.com", "http://")) assertFalse(a, plainHttpOutsideHome(a))
    }

    @Test fun plainHttpOnTheInternetIsFlagged() {
        for (a in listOf("http://music.example.com", "http://203.0.113.7:8096", "HTTP://jf.duckdns.org", "http://[2001:db8::1]")) assertTrue(a, plainHttpOutsideHome(a))
    }
}
