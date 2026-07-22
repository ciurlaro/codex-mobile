package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.codex.BuiltInToolResult
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

internal enum class MutationState { PREPARED, DISPATCHED, SUCCEEDED, FAILED, INDETERMINATE }

internal data class JournalEntry(
    val state: MutationState,
    val result: BuiltInToolResult?,
    val beforeHash: String?,
    val afterHash: String?,
)

private data class StoredJournalEntry(
    val argumentsHash: String,
    val plugin: String,
    val tool: String,
    val entry: JournalEntry,
)

internal class BuiltInMutationJournal(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "built-in-mutations.sqlite", null, 1) {

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
    fun prepare(call: BuiltInToolCall): JournalEntry? {
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
            put("state", MutationState.PREPARED.name)
        }
        check(database.insertOrThrow("mutations", null, values) >= 0)
        return null
    }

    @Synchronized
    fun find(call: BuiltInToolCall): JournalEntry? = read(writableDatabase, call)?.let { stored ->
        require(
            stored.argumentsHash == call.argumentsHash &&
                stored.plugin == call.pluginId && stored.tool == call.tool,
        ) {
            "Protocol error: a built-in call ID was reused with different arguments or tool"
        }
        stored.entry
    }

    @Synchronized
    fun dispatched(call: BuiltInToolCall, beforeHash: String? = null, afterHash: String? = null) {
        update(call, MutationState.DISPATCHED, null, beforeHash, afterHash)
    }

    @Synchronized
    fun finish(
        call: BuiltInToolCall,
        state: MutationState,
        result: BuiltInToolResult,
        beforeHash: String? = null,
        afterHash: String? = null,
    ) {
        require(state in TERMINAL_STATES)
        update(call, state, result, beforeHash, afterHash)
    }

    private fun update(
        call: BuiltInToolCall,
        state: MutationState,
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
                entry = JournalEntry(
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
                            is BuiltInToolContent.Text -> buildJsonObject {
                                put("type", "text")
                                put("value", item.value)
                            }
                            is BuiltInToolContent.Image -> buildJsonObject {
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
                    "text" -> BuiltInToolContent.Text(item["value"]!!.jsonPrimitive.content)
                    "image" -> BuiltInToolContent.Image(item["value"]!!.jsonPrimitive.content)
                    else -> error("Invalid mutation journal result")
                }
            },
            success = json["success"]!!.jsonPrimitive.content.toBooleanStrict(),
        )
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            MutationState.SUCCEEDED,
            MutationState.FAILED,
            MutationState.INDETERMINATE,
        )
    }
}
