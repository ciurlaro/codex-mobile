package io.github.ciurlaro.codexmobile.app

import io.github.ciurlaro.codexmobile.core.AgentCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatModelsTest {
    @Test
    fun effortLabelsAreCentralizedAndUnknownValuesRemainReadable() {
        assertEquals("Extra High", effortLabel("xhigh"))
        assertEquals("Ultra", effortLabel("ultra"))
        assertEquals("Custom", effortLabel("custom"))
    }

    @Test
    fun atQueryAndPlusPickerShareTheSameTypedCapabilityCatalog() {
        assertEquals("we", selectedTagQuery("Check this @we"))
        assertEquals("Check this", removeSelectedTagQuery("Check this @we"))
        assertNull(selectedTagQuery("Check this normally"))
        assertEquals("web_search", AgentCapability.WEB_SEARCH.id)
        assertEquals("Web search", AgentCapability.WEB_SEARCH.displayLabel)
    }
}
