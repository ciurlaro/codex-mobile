package io.github.ciurlaro.codexmobile.appserver.host.android

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackConnectProxyTest {
    @Test
    fun allowsPublicDestinationsAndRejectsNonPublicRanges() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
            assertTrue(it, InetAddress.getByName(it).isPublicProxyAddress())
        }
        listOf(
            "127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.1.1", "172.16.0.1",
            "192.168.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "::1", "fd00::1",
            "2001:db8::1",
        ).forEach { assertFalse(it, InetAddress.getByName(it).isPublicProxyAddress()) }
    }
}
