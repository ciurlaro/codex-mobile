package io.github.ciurlaro.codexmobile.app.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElicitationDialogTest {
    @Test
    fun `questions advance one page at a time and stop at the last`() {
        assertEquals(1, nextElicitationPage(page = 0, pageCount = 3))
        assertEquals(2, nextElicitationPage(page = 1, pageCount = 3))
        assertNull(nextElicitationPage(page = 2, pageCount = 3))
    }
}
