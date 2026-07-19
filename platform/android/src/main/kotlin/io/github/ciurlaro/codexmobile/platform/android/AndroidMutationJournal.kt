package io.github.ciurlaro.codexmobile.platform.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import io.github.ciurlaro.codexmobile.core.MutationJournal
import io.github.ciurlaro.codexmobile.core.MutationRecord
import io.github.ciurlaro.codexmobile.core.MutationRecordId
import io.github.ciurlaro.codexmobile.core.MutationState
import io.github.ciurlaro.codexmobile.core.ResourceScopeId
import io.github.ciurlaro.codexmobile.core.ToolCallId
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidMutationJournal(
    context: Context,
    databaseFile: File = File(context.noBackupFilesDir, DATABASE_NAME),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MutationJournal, Closeable {
    private val helper = Helper(context.applicationContext, databaseFile.absolutePath)

    override suspend fun create(record: MutationRecord) = withContext(Dispatchers.IO) {
        require(record.state == MutationState.PREPARED) { "New mutation record must be Prepared" }
        require(record.outcome == null && !record.acknowledged) { "New mutation record has terminal data" }
        record.validate()
        val now = nowMillis()
        val values = ContentValues().apply {
            put(COLUMN_RECORD_ID, record.id.value)
            put(COLUMN_CALL_ID, record.callId.value)
            put(COLUMN_TOOL_NAME, record.toolName)
            put(COLUMN_SCOPE_ID, record.scopeId.value)
            put(COLUMN_FINGERPRINT, record.planFingerprint)
            put(COLUMN_RECOVERY_PAYLOAD, record.recoveryPayload)
            put(COLUMN_STATE, record.state.name)
            putNull(COLUMN_OUTCOME)
            put(COLUMN_CREATED_AT, now)
            put(COLUMN_UPDATED_AT, now)
            put(COLUMN_ACKNOWLEDGED, 0)
        }
        helper.writableDatabase.insertOrThrow(TABLE, null, values)
        Unit
    }

    override suspend fun transition(
        recordId: MutationRecordId,
        expected: MutationState,
        next: MutationState,
        outcome: String?,
        acknowledged: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(expected.canTransitionTo(next)) { "Invalid mutation state transition" }
        require(outcome == null || outcome.length <= MAX_OUTCOME_CHARS) { "Mutation outcome is too large" }
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COLUMN_STATE, next.name)
                if (outcome == null) putNull(COLUMN_OUTCOME) else put(COLUMN_OUTCOME, outcome)
                put(COLUMN_UPDATED_AT, nowMillis())
                put(COLUMN_ACKNOWLEDGED, if (acknowledged) 1 else 0)
            }
            val changed = database.update(
                TABLE,
                values,
                "$COLUMN_RECORD_ID = ? AND $COLUMN_STATE = ?",
                arrayOf(recordId.value, expected.name),
            )
            check(changed == 1) { "Mutation record is missing or changed concurrently" }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun find(recordId: MutationRecordId): MutationRecord? =
        withContext(Dispatchers.IO) {
            queryRecords("$COLUMN_RECORD_ID = ?", arrayOf(recordId.value)).singleOrNull()
        }

    override suspend fun unresolved(): List<MutationRecord> = withContext(Dispatchers.IO) {
        queryRecords(
            "$COLUMN_STATE IN (?, ?, ?)",
            arrayOf(
                MutationState.PREPARED.name,
                MutationState.EXECUTING.name,
                MutationState.UNKNOWN.name,
            ),
        )
    }

    override suspend fun visible(): List<MutationRecord> = withContext(Dispatchers.IO) {
        queryRecords("$COLUMN_ACKNOWLEDGED = 0", null)
    }

    override suspend fun acknowledge(recordId: MutationRecordId) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(COLUMN_ACKNOWLEDGED, 1)
            put(COLUMN_UPDATED_AT, nowMillis())
        }
        check(
            helper.writableDatabase.update(
                TABLE,
                values,
                "$COLUMN_RECORD_ID = ?",
                arrayOf(recordId.value),
            ) == 1,
        ) { "Mutation record is unavailable" }
    }

    override suspend fun pruneResolved(updatedBeforeMillis: Long): Int =
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete(
                TABLE,
                "$COLUMN_ACKNOWLEDGED = 1 AND $COLUMN_UPDATED_AT < ? AND $COLUMN_STATE IN (?, ?)",
                arrayOf(
                    updatedBeforeMillis.toString(),
                    MutationState.SUCCEEDED.name,
                    MutationState.FAILED.name,
                ),
            )
        }

    internal suspend fun allRecords(): List<MutationRecord> = withContext(Dispatchers.IO) {
        queryRecords(null, null)
    }

    internal suspend fun limitToCurrentPageCount() = withContext(Dispatchers.IO) {
        val database = helper.writableDatabase
        val count = database.rawQuery("PRAGMA page_count", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        database.rawQuery("PRAGMA max_page_count=$count", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == count)
        }
    }

    override fun close() = helper.close()

    private fun queryRecords(selection: String?, selectionArgs: Array<String>?): List<MutationRecord> {
        return helper.readableDatabase.query(
            TABLE,
            PROJECTION,
            selection,
            selectionArgs,
            null,
            null,
            "rowid ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.mutationRecord())
            }
        }
    }

    private fun Cursor.mutationRecord() = MutationRecord(
        id = MutationRecordId(getString(getColumnIndexOrThrow(COLUMN_RECORD_ID))),
        callId = ToolCallId(getString(getColumnIndexOrThrow(COLUMN_CALL_ID))),
        toolName = getString(getColumnIndexOrThrow(COLUMN_TOOL_NAME)),
        scopeId = ResourceScopeId(getString(getColumnIndexOrThrow(COLUMN_SCOPE_ID))),
        planFingerprint = getString(getColumnIndexOrThrow(COLUMN_FINGERPRINT)),
        recoveryPayload = getString(getColumnIndexOrThrow(COLUMN_RECOVERY_PAYLOAD)),
        state = MutationState.valueOf(getString(getColumnIndexOrThrow(COLUMN_STATE))),
        outcome = getColumnIndexOrThrow(COLUMN_OUTCOME).let { if (isNull(it)) null else getString(it) },
        sequence = getLong(getColumnIndexOrThrow(COLUMN_SEQUENCE)),
        createdAtMillis = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        updatedAtMillis = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
        acknowledged = getInt(getColumnIndexOrThrow(COLUMN_ACKNOWLEDGED)) == 1,
    )

    private fun MutationRecord.validate() {
        require(id.value.isNotBlank() && id.value.length <= MAX_ID_CHARS)
        require(callId.value.isNotBlank() && callId.value.length <= MAX_ID_CHARS)
        require(toolName.isNotBlank() && toolName.length <= MAX_TOOL_NAME_CHARS)
        require(scopeId.value.isNotBlank() && scopeId.value.length <= MAX_ID_CHARS)
        require(planFingerprint.isNotBlank() && planFingerprint.length <= MAX_FINGERPRINT_CHARS)
        require(
            recoveryPayload.isNotBlank() &&
                recoveryPayload.toByteArray(StandardCharsets.UTF_8).size <= MAX_RECOVERY_BYTES,
        ) { "Mutation recovery data is invalid" }
    }

    private class Helper(context: Context, path: String) : SQLiteOpenHelper(
        context,
        path,
        null,
        DATABASE_VERSION,
        DatabaseErrorHandler { throw SQLiteException("Mutation journal is corrupt") },
    ) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
            database.rawQuery("PRAGMA busy_timeout=1000", null).use { it.moveToFirst() }
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(CREATE_TABLE_V2)
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) {
                throw SQLiteException("Unsupported mutation journal upgrade")
            }
            database.execSQL("ALTER TABLE $TABLE ADD COLUMN $COLUMN_OUTCOME TEXT")
            database.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COLUMN_CREATED_AT INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COLUMN_UPDATED_AT INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COLUMN_ACKNOWLEDGED INTEGER NOT NULL DEFAULT 0 " +
                    "CHECK ($COLUMN_ACKNOWLEDGED IN (0, 1))",
            )
        }

        override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Mutation journal downgrade is unsupported")
        }
    }

    internal companion object {
        const val DATABASE_NAME = "mutation-journal.db"
        const val DATABASE_VERSION = 2
        const val TABLE = "mutation_records"
        const val COLUMN_SEQUENCE = "sequence"
        const val COLUMN_RECORD_ID = "record_id"
        const val COLUMN_CALL_ID = "call_id"
        const val COLUMN_TOOL_NAME = "tool_name"
        const val COLUMN_SCOPE_ID = "scope_id"
        const val COLUMN_FINGERPRINT = "plan_fingerprint"
        const val COLUMN_RECOVERY_PAYLOAD = "recovery_payload"
        const val COLUMN_STATE = "state"
        const val COLUMN_OUTCOME = "outcome"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_ACKNOWLEDGED = "acknowledged"
        const val MAX_ID_CHARS = 512
        const val MAX_TOOL_NAME_CHARS = 128
        const val MAX_FINGERPRINT_CHARS = 256
        const val MAX_RECOVERY_BYTES = 64 * 1024
        const val MAX_OUTCOME_CHARS = 4 * 1024
        val PROJECTION = arrayOf(
            "rowid AS $COLUMN_SEQUENCE",
            COLUMN_RECORD_ID,
            COLUMN_CALL_ID,
            COLUMN_TOOL_NAME,
            COLUMN_SCOPE_ID,
            COLUMN_FINGERPRINT,
            COLUMN_RECOVERY_PAYLOAD,
            COLUMN_STATE,
            COLUMN_OUTCOME,
            COLUMN_CREATED_AT,
            COLUMN_UPDATED_AT,
            COLUMN_ACKNOWLEDGED,
        )
        const val CREATE_TABLE_V1 =
            "CREATE TABLE $TABLE (" +
                "$COLUMN_RECORD_ID TEXT PRIMARY KEY NOT NULL," +
                "$COLUMN_CALL_ID TEXT NOT NULL," +
                "$COLUMN_TOOL_NAME TEXT NOT NULL," +
                "$COLUMN_SCOPE_ID TEXT NOT NULL," +
                "$COLUMN_FINGERPRINT TEXT NOT NULL," +
                "$COLUMN_RECOVERY_PAYLOAD TEXT NOT NULL," +
                "$COLUMN_STATE TEXT NOT NULL)"
        const val CREATE_TABLE_V2 =
            "CREATE TABLE $TABLE (" +
                "$COLUMN_RECORD_ID TEXT PRIMARY KEY NOT NULL," +
                "$COLUMN_CALL_ID TEXT NOT NULL," +
                "$COLUMN_TOOL_NAME TEXT NOT NULL," +
                "$COLUMN_SCOPE_ID TEXT NOT NULL," +
                "$COLUMN_FINGERPRINT TEXT NOT NULL," +
                "$COLUMN_RECOVERY_PAYLOAD TEXT NOT NULL," +
                "$COLUMN_STATE TEXT NOT NULL CHECK ($COLUMN_STATE IN " +
                "('PREPARED','EXECUTING','SUCCEEDED','FAILED','UNKNOWN'))," +
                "$COLUMN_OUTCOME TEXT," +
                "$COLUMN_CREATED_AT INTEGER NOT NULL," +
                "$COLUMN_UPDATED_AT INTEGER NOT NULL," +
                "$COLUMN_ACKNOWLEDGED INTEGER NOT NULL CHECK ($COLUMN_ACKNOWLEDGED IN (0, 1)))"
    }
}
