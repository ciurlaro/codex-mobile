package io.github.ciurlaro.codexmobile.platform.android

import android.content.Context
import android.net.Uri
import io.github.ciurlaro.codexmobile.core.DeviceTool
import io.github.ciurlaro.codexmobile.core.MutationJournal
import io.github.ciurlaro.codexmobile.core.ResourceScopeId

class AndroidPlatform(context: Context) {
    private val appContext = context.applicationContext

    fun launchProcess(command: List<String>, environment: Map<String, String>): Process =
        TODO("Step 01: prepare and launch the pinned Codex runtime in app-private storage")

    fun persistScope(treeUri: Uri): ResourceScopeId =
        TODO("Step 02: persist a validated SAF read grant and return an opaque scope ID")

    fun revokeScope(scopeId: ResourceScopeId) {
        TODO("Step 02: release the matching persisted grant and local metadata")
    }

    fun deviceTools(): List<DeviceTool> =
        TODO("Steps 02–03: return only locally registered, scope-validating Android tools")

    fun mutationJournal(): MutationJournal =
        TODO("Step 04: create the durable Android journal only when recovery work begins")
}
