package io.github.ciurlaro.codexmobile.app.security.display

import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalTextSanitizerTest {
    @Test
    fun controlsAndUnicodeFormatCharactersAreVisibleWithoutDamagingEmoji() {
        val source = "safe\n\u202Eabc\u202C\u2028😀\uDB40\uDC01"

        assertEquals(
            "safe\\u{A}\\u{202E}abc\\u{202C}\\u{2028}😀\\u{E0001}",
            source.toApprovalDisplayText(),
        )
    }
}
