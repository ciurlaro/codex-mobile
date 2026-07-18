package io.github.ciurlaro.codexmobile.agent.codex

import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("Step 01 is not implemented; ignored tests never satisfy the exit gate")
class Step01ProtocolContractTest {
    @Test
    fun `frames partial batched CRLF and UTF-8 input`(): Unit =
        TODO("S01-IO-02")

    @Test
    fun `correlates responses while preserving notification order`(): Unit =
        TODO("S01-IO-01")

    @Test
    fun `rejects malformed unknown and orphan messages without deadlock`(): Unit =
        TODO("S01-IO-03")

    @Test
    fun `bounds large messages slow consumers and cancellation races`(): Unit =
        TODO("S01-IO-05, S01-IO-06")

    @Test
    fun `translates authentication session stream completion and failure events`(): Unit =
        TODO("S01-AUTH-01, S01-SES-01, S01-SES-02, S01-SES-06")

    @Test
    fun `rejects blank prompts and preserves Unicode and multiline prompts`(): Unit =
        TODO("S01-SES-03")
}
