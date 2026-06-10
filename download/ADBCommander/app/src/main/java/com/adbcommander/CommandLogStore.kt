package com.adbcommander

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists a history of executed ADB shell commands.
 *
 * Each log entry contains:
 * - **timestamp** — epoch millis when the command was executed
 * - **command**   — the full shell command string that was sent
 * - **isSuccess** — whether the ADB execution returned exit code 0
 *
 * Storage: a JSON file in the app's internal storage directory.
 * The list is capped at [MAX_LOGS] entries; oldest entries are pruned
 * when the cap is exceeded.
 */
class CommandLogStore(private val context: Context) {

    data class LogEntry(
        val timestamp: Long,
        val command: String,
        val isSuccess: Boolean
    )

    companion object {
        private const val TAG = "CommandLogStore"
        private const val FILE_NAME = "execution_logs.json"
        private const val MAX_LOGS = 200

        /** Format a log entry's timestamp for display. */
        fun formatTimestamp(epochMillis: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(epochMillis))
        }
    }

    private val logFile = File(context.filesDir, FILE_NAME)

    /**
     * Return all stored log entries, newest first.
     */
    fun getLogs(): List<LogEntry> {
        if (!logFile.exists()) return emptyList()
        return try {
            val json = logFile.readText()
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try {
                    val obj = array.getJSONObject(i)
                    LogEntry(
                        timestamp = obj.getLong("timestamp"),
                        command = obj.getString("command"),
                        isSuccess = obj.getBoolean("isSuccess")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed log entry at index $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logs", e)
            emptyList()
        }
    }

    /**
     * Add a new log entry at the top of the list.
     * If the list exceeds [MAX_LOGS], the oldest entries are pruned.
     */
    fun addLog(command: String, isSuccess: Boolean) {
        val logs = getLogs().toMutableList()
        logs.add(0, LogEntry(System.currentTimeMillis(), command, isSuccess))
        while (logs.size > MAX_LOGS) logs.removeAt(logs.lastIndex)
        saveLogs(logs)
    }

    /**
     * Remove all stored log entries.
     */
    fun clearLogs() {
        if (logFile.exists()) logFile.delete()
    }

    private fun saveLogs(logs: List<LogEntry>) {
        try {
            val array = JSONArray()
            logs.forEach { entry ->
                array.put(JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("command", entry.command)
                    put("isSuccess", entry.isSuccess)
                })
            }
            logFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logs", e)
        }
    }
}
