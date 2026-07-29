package io.github.ciurlaro.codexmobile.extension.host

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.provider.api.ProviderContent
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationEntry
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationJournal
import io.github.ciurlaro.codexmobile.provider.api.ProviderMutationState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private data class StoredJournalEntry(
    val argumentsHash: String,
    val plugin: String,
    val tool: String,
    val entry: ProviderMutationEntry,
)

class BuiltInMutationJournal(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "built-in-mutations.sqlite", null, 1),
    ProviderMutationJournal,
    AutoCloseable {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE mutations (
                thread_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                call_id TEXT NOT NULL,
                arguments_hash TEXT NOT NULL,
                plugin TEXT NOT NULL,
                tool TEXT NOT NULL,
                state TEXT NOT NULL,
                result TEXT,
                before_hash TEXT,
                after_hash TEXT,
                PRIMARY KEY (thread_id, turn_id, call_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    override fun prepare(call: BuiltInToolCall): ProviderMutationEntry? {
        val database = writableDatabase
        read(database, call)?.let { stored ->
            require(
                stored.argumentsHash == call.argumentsHash &&
                    stored.plugin == call.pluginId && stored.tool == call.tool,
            ) {
                "Protocol error: a built-in call ID was reused with different arguments or tool"
            }
            return stored.entry
        }
        val values = ContentValues().apply {
            put("thread_id", call.threadId)
            put("turn_id", call.turnId)
            put("call_id", call.callId)
            put("arguments_hash", call.argumentsHash)
            put("plugin", call.pluginId)
            put("tool", call.tool)
            put("state", ProviderMutationState.PREPARED.name)
        }
        check(database.insertOrThrow("mutations", null, values) >= 0)
        return null
    }

    @Synchronized
    override fun find(call: BuiltInToolCall): ProviderMutationEntry? = read(writableDatabase, call)?.let { stored ->
        require(
            stored.argumentsHash == call.argumentsHash &&
                stored.plugin == call.pluginId && stored.tool == call.tool,
        ) {
            "Protocol error: a built-in call ID was reused with different arguments or tool"
        }
        stored.entry
    }

    @Synchronized
    override fun dispatched(call: BuiltInToolCall, beforeHash: String?, afterHash: String?) {
        update(call, ProviderMutationState.DISPATCHED, null, beforeHash, afterHash)
    }

    @Synchronized
    override fun finish(
        call: BuiltInToolCall,
        state: ProviderMutationState,
        result: BuiltInToolResult,
        beforeHash: String?,
        afterHash: String?,
    ) {
        require(state in TERMINAL_STATES)
        update(call, state, result, beforeHash, afterHash)
    }

    @Synchronized
    fun compact(pluginId: String) {
        require(pluginId.isNotBlank()) { "Plugin ID must not be blank" }
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                "mutations",
                "plugin=? AND state=?",
                arrayOf(pluginId, ProviderMutationState.PREPARED.name),
            )
            database.update(
                "mutations",
                ContentValues().apply { put("state", ProviderMutationState.INDETERMINATE.name) },
                "plugin=? AND state=?",
                arrayOf(pluginId, ProviderMutationState.DISPATCHED.name),
            )
            database.update(
                "mutations",
                ContentValues().apply {
                    putNull("result")
                    putNull("before_hash")
                    putNull("after_hash")
                },
                "plugin=?",
                arrayOf(pluginId),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun update(
        call: BuiltInToolCall,
        state: ProviderMutationState,
        result: BuiltInToolResult?,
        beforeHash: String?,
        afterHash: String?,
    ) {
        val values = ContentValues().apply {
            put("state", state.name)
            if (result != null) put("result", encode(result))
            if (beforeHash != null) put("before_hash", beforeHash)
            if (afterHash != null) put("after_hash", afterHash)
        }
        check(
            writableDatabase.update(
                "mutations",
                values,
                "thread_id=? AND turn_id=? AND call_id=? AND arguments_hash=?",
                arrayOf(call.threadId, call.turnId, call.callId, call.argumentsHash),
            ) == 1,
        ) { "Mutation journal entry disappeared" }
    }

    private fun read(database: SQLiteDatabase, call: BuiltInToolCall): StoredJournalEntry? =
        database.query(
            "mutations",
            arrayOf("arguments_hash", "plugin", "tool", "state", "result", "before_hash", "after_hash"),
            "thread_id=? AND turn_id=? AND call_id=?",
            arrayOf(call.threadId, call.turnId, call.callId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            StoredJournalEntry(
                argumentsHash = cursor.getString(0),
                plugin = cursor.getString(1),
                tool = cursor.getString(2),
                entry = ProviderMutationEntry(
                    state = enumValueOf(cursor.getString(3)),
                    result = cursor.getString(4)?.let(::decode),
                    beforeHash = cursor.getString(5),
                    afterHash = cursor.getString(6),
                ),
            )
        }

    private fun encode(result: BuiltInToolResult): String = buildJsonObject {
        put("success", result.success)
        put(
            "content",
            buildJsonArray {
                result.content.forEach { item ->
                    add(
                        when (item) {
                            is ProviderContent.Text -> buildJsonObject {
                                put("type", "text")
                                put("value", item.value)
                            }
                            is ProviderContent.Image -> buildJsonObject {
                                put("type", "image")
                                put("value", item.dataUrl)
                            }
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decode(value: String): BuiltInToolResult {
        val json = Json.parseToJsonElement(value).jsonObject
        return BuiltInToolResult(
            content = json["content"]!!.jsonArray.map { raw ->
                val item = raw.jsonObject
                when (item["type"]!!.jsonPrimitive.content) {
                    "text" -> ProviderContent.Text(item["value"]!!.jsonPrimitive.content)
                    "image" -> ProviderContent.Image(item["value"]!!.jsonPrimitive.content)
                    else -> error("Invalid mutation journal result")
                }
            },
            success = json["success"]!!.jsonPrimitive.content.toBooleanStrict(),
        )
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            ProviderMutationState.SUCCEEDED,
            ProviderMutationState.FAILED,
            ProviderMutationState.INDETERMINATE,
        )
    }
}
