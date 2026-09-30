package com.friday.connect.assistant

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues

/** Durable claim before execution: after a process crash, an uncertain command is never replayed. */
class RemoteCommandJournal(context: Context) : SQLiteOpenHelper(context, "remote-command-journal.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE commands(id TEXT PRIMARY KEY, received INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun claim(device: String, request: String): Boolean {
        val values = ContentValues().apply { put("id", "$device:$request"); put("received", System.currentTimeMillis()) }
        return writableDatabase.insertWithOnConflict("commands", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized override fun close() { super.close() }
}
