package io.github.ciurlaro.codexmobile.core

import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("Step 04 is not implemented; ignored tests never satisfy the exit gate")
class Step04MutationJournalContractTest {
    @Test
    fun `permits only the documented mutation state transitions`() =
        TODO("S04-STATE-01, S04-STATE-02")

    @Test
    fun `requires durable prepared record before dispatch`() =
        TODO("S04-DB-01")

    @Test
    fun `does not overwrite conflicting intent for duplicate call ID`() =
        TODO("S04-RET-03")

    @Test
    fun `denies retry unless the tool proves it safe`() =
        TODO("S04-RET-01")

    @Test
    fun `keeps unknown as a valid non-terminal-retry outcome`() =
        TODO("S04-REC-03")
}
