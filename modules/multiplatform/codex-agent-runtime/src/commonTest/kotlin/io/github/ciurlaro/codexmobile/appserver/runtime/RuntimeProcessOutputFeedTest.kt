package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeProcessOutputFeedTest {
    @Test
    fun ignoresEmptyLinesCountsUtf8BytesAndStopsAfterFailure() {
        val received = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val feed = RuntimeProcessOutputFeed(
            maxBytes = 4,
            onLine = received::add,
            onFailure = failures::add,
        )

        feed.onOutput("")
        feed.onOutput(" \t")
        feed.onOutput("éé")
        feed.onOutput("ééé")
        feed.onOutput("later")
        RuntimeProcessOutputFeed(
            onLine = received::add,
            onFailure = failures::add,
        ).onOutput("\uFFFD")

        assertEquals(listOf(" \t", "éé"), received)
        assertEquals(2, failures.size)
        failures.forEach { assertIs<IllegalStateException>(it) }
    }
}
