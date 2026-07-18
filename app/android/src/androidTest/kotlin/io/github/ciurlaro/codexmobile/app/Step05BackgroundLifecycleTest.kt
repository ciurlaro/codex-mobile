package io.github.ciurlaro.codexmobile.app

import org.junit.Ignore
import org.junit.Test

@Ignore("Step 05 is deferred; ignored tests never satisfy the exit gate")
class Step05BackgroundLifecycleTest {
    @Test
    fun explicitStartCreatesOneServiceAndHandlesNotificationPermission(): Unit =
        TODO("S05-START-01 through S05-START-03")

    @Test
    fun notificationIsAccuratePrivateAndStopsWork(): Unit =
        TODO("S05-NOT-01, S05-NOT-02")

    @Test
    fun bindingRecreationAndMultipleActivitiesKeepOneOwner(): Unit =
        TODO("S05-BIND-01, S05-BIND-02")

    @Test
    fun taskRemovalForceStopLowMemoryAndRebootBehaveAsDocumented(): Unit =
        TODO("S05-LIFE-01 through S05-LIFE-04")

    @Test
    fun networkLossApprovalTimingAndApiRestrictionsFailSafely(): Unit =
        TODO("S05-NET-01 through S05-COMP-01")

    @Test
    fun resourcesAndComponentExposureRemainBounded(): Unit =
        TODO("S05-RES-01, S05-SEC-01")
}
