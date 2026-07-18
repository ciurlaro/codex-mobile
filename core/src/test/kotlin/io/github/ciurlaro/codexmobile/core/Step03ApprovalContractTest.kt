package io.github.ciurlaro.codexmobile.core

import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("Step 03 is not implemented; ignored tests never satisfy the exit gate")
class Step03ApprovalContractTest {
    @Test
    fun `mutations require user approval by default`() =
        TODO("S03-POL-01")

    @Test
    fun `unknown tool and cross-scope plan fail closed`() =
        TODO("S03-POL-02")

    @Test
    fun `approval must match call and resolved plan fingerprint`() =
        TODO("S03-APP-04")

    @Test
    fun `approval is consumed once and cannot authorize altered intent`() =
        TODO("S03-APP-05")

    @Test
    fun `duplicate call ID never implies replay safety`() =
        TODO("S03-COR-01")
}
