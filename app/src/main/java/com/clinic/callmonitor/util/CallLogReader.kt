package com.clinic.callmonitor.util

import android.content.Context
import android.provider.CallLog

data class SystemCallEntry(
    val phoneNumber: String,
    val callType: String, // INCOMING / OUTGOING / MISSED
    val startTimestamp: Long, // بالميلي ثانية
    val durationSeconds: Int
)

object CallLogReader {

    /** بيرجع كل المكالمات اللي حصلت بعد وقت معيّن (afterTimestamp بالميلي ثانية) */
    fun getCallsSince(context: Context, afterTimestamp: Long): List<SystemCallEntry> {
        val results = mutableListOf<SystemCallEntry>()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(afterTimestamp.toString()),
            "${CallLog.Calls.DATE} ASC"
        )

        cursor?.use {
            val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

            while (it.moveToNext()) {
                val number = it.getString(numberIdx) ?: "unknown"
                val typeInt = it.getInt(typeIdx)
                val date = it.getLong(dateIdx)
                val duration = it.getInt(durationIdx)

                val typeStr = when (typeInt) {
                    CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                    CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    else -> "UNKNOWN"
                }

                results.add(SystemCallEntry(number, typeStr, date, duration))
            }
        }

        return results
    }
}
