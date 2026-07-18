package io.github.ciurlaro.codexmobile.app

import org.junit.Ignore
import org.junit.Test

@Ignore("Step 01 is not implemented; ignored tests never satisfy the exit gate")
class Step01RuntimePremiseDeviceTest {
    @Test
    fun runtimePackagingPreparationAndChecksum(): Unit =
        TODO("S01-RUN-01 through S01-RUN-04")

    @Test
    fun processStartStopRestartAndUnexpectedExit(): Unit =
        TODO("S01-RUN-05 through S01-RUN-08")

    @Test
    fun subscriptionAuthenticationFailuresAndPersistence(): Unit =
        TODO("S01-AUTH-01 through S01-AUTH-06")

    @Test
    fun promptStreamingCancellationAndActivityRecreation(): Unit =
        TODO("S01-SES-01 through S01-SES-06")

    @Test
    fun restartRecordsAuthenticationAndSessionSurvival(): Unit =
        TODO("S01-AUTH-06, S01-SES-07")

    @Test
    fun runtimeCredentialsComponentsAndLogsRemainPrivate(): Unit =
        TODO("S01-AUTH-05, S01-SEC-01")
}
