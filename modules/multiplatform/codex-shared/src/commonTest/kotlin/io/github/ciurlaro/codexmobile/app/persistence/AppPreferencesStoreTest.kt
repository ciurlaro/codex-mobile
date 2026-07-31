package io.github.ciurlaro.codexmobile.app.persistence

import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.buffer

class AppPreferencesStoreTest {
    @Test
    fun defaultsAndEverySupportedValueRoundTrip() = withPreferences { store ->
        assertEquals(AppPreferenceState(), store.load())

        store.saveSelection("gpt-5", "high", "fast", AgentApprovalPreset.NEVER)
        store.savePinnedConversationIds(setOf("one", "two"))
        store.saveRecentInvocationKeys(listOf("five", "four", "three", "two", "one"))
        store.setHadAuthenticatedSession(true)
        store.setAuthenticationHandoffPending(true)
        store.savePendingPluginSetups(mapOf("plugin" to setOf("connector")))

        assertEquals(
            AppPreferenceState(
                selectedModel = "gpt-5",
                selectedEffort = "high",
                selectedSpeedTier = "fast",
                pinnedConversationIds = setOf("one", "two"),
                recentInvocationKeys = listOf("five", "four", "three", "two"),
                approvalPreset = AgentApprovalPreset.NEVER,
                pendingPluginSetups = mapOf("plugin" to setOf("connector")),
                hadAuthenticatedSession = true,
                authenticationHandoffPending = true,
            ),
            store.load(),
        )
    }

    @Test
    fun corruptFilesFallBackToSafeDefaults() = withPreferences(
        prepare = { path ->
            FileSystem.SYSTEM.sink(path).buffer().use { it.writeUtf8("not a preferences protobuf") }
        },
    ) { store ->
        assertEquals(AppPreferenceState(), store.load())
    }

    @Test
    fun concurrentEditsPreserveIndependentPreferenceGroups() = withPreferences { store ->
        coroutineScope {
            launch { store.saveSelection("gpt-5", "medium", null, AgentApprovalPreset.AUTO_REVIEW) }
            launch { store.savePinnedConversationIds(setOf("thread")) }
            launch { store.setHadAuthenticatedSession(true) }
        }

        val saved = store.load()
        assertEquals("gpt-5", saved.selectedModel)
        assertEquals(setOf("thread"), saved.pinnedConversationIds)
        assertEquals(true, saved.hadAuthenticatedSession)
    }
}

private fun withPreferences(
    prepare: (Path) -> Unit = {},
    test: suspend (AppPreferencesStore) -> Unit,
) = runBlocking {
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "codex-preferences-${Random.nextLong()}"
    val path = directory / "chat-ui.preferences_pb"
    val job = SupervisorJob()
    FileSystem.SYSTEM.createDirectories(directory)
    prepare(path)
    try {
        test(AppPreferencesStore(path, CoroutineScope(job + Dispatchers.Default)))
    } finally {
        job.cancelAndJoin()
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }
}
