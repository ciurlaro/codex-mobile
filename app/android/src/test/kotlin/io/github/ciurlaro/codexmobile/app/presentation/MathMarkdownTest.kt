package io.github.ciurlaro.codexmobile.app.presentation

import io.github.ciurlaro.codexmobile.app.presentation.formatting.decodeMathLink
import io.github.ciurlaro.codexmobile.app.presentation.formatting.normalizeMathMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathMarkdownTest {
    @Test
    fun displayAndInlineMathAreNormalizedWithoutChangingCurrencyOrCode() {
        val result = """
            A book costs ${'$'}12 and `${'$'}x${'$'}` is code.
            Inline ${'$'}x^2 + 1${'$'}.
            \[x = \frac{12}{0.8}\]
        """.trimIndent().normalizeMathMarkdown()

        assertTrue("${'$'}12" in result)
        assertTrue("`${'$'}x${'$'}`" in result)
        assertTrue("```math\nx = \\frac{12}{0.8}\n```" in result)
        val link = Regex("codex-math:[A-Za-z0-9_-]+").find(result)!!.value
        assertEquals("x^2 + 1", decodeMathLink(link))
    }

    @Test
    fun malformedDelimitersRemainVisible() {
        val source = "Price ${'$'}15 and unfinished \\[x + 1"
        val result = source.normalizeMathMarkdown()
        assertEquals(source, result)
        assertFalse("```math" in result)
    }
}
