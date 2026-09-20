package com.clinic.callmonitor.service

import android.content.Context
import androidx.work.*
import com.clinic.callmonitor.CallMonitorApp
import com.clinic.callmonitor.network.ApiClient
import com.clinic.callmonitor.network.CallLogEntry
import com.clinic.callmonitor.network.DeletionEvent
import com.clinic.callmonitor.util.CallLogReader
import com.clinic.callmonitor.util.DeviceRecordingFile
import com.clinic.callmonitor.util.RecordingFolderScanner
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * الفكرة: تطبيق الهاتف الافتراضي في سامسونج بيسجل المكالمة فعليًا (بصلاحيات
 * نظام مش متاحة لينا) ويحفظها في مجلد عام على الجهاز. إحنا مش بنسجل حاجة -
 * بس بنراقب المجلد ده + سجل المكالمات، ونربط كل مكالمة بملفها ونرفعهم.
 *
 * حدود مهمة:
 * - لو الموظف قفل خاصية "تسجيل المكالمات" من إعدادات تطبيق الهاتف، مفيش
 *   ملف هيتعمل أصلاً - إحنا هنكتشف الغياب ده ونبلغ عنه (مش نمنعه).
 * - المطابقة بتعتمد على التوقيت (اسم الملف أو تاريخ التعديل) - في حالات
 *   نادرة (مكالمتين قريبين من بعض جدًا) ممكن يحصل خلط، فراجع يدويًا لو
 *   لاحظت رقم غلط قدام تسجيل.
 */
class RecordingSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val KEY_LAST_SYNC = "recording_sync_last_timestamp"
        private const val KEY_UPLOADED_PREFIX = "call_uploaded_"
        private const val KEY_RECORDING_UPLOADED_PREFIX = "recording_uploaded_"
        private const val KEY_DELETION_REPORTED_PREFIX = "deletion_reported_"
        private const val MATCH_WINDOW_MS = 5 * 60 * 1000L // 5 دقائق نافذة مطابقة

        fun schedulePeriodic(context: Context) {
            // النبضة الدورية كل 15 دقيقة - بدون شرط الشبكة عشان يبعتها فور ما تتوفر
            val request = PeriodicWorkRequestBuilder<RecordingSyncWorker>(15, TimeUnit.MINUTES)
                .build() // لا نشترط الشبكة هنا - الـ Worker نفسه هيتعامل مع الفشل بهدوء
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "recording_sync_periodic", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** تشغيل فوري بعد إغلاق مكالمة - بتأخير للسماح لسامسونج/شاومي بإنهاء كتابة الملف */
        fun scheduleOneTimeAfterCall(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecordingSyncWorker>()
                .setInitialDelay(45, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "recording_sync_onetime", ExistingWorkPolicy.REPLACE, request
            )
        }

        /** تشغيل فوري بدون أي تأخير - للاختبار اليدوي من زرار "مزامنة الآن" */
        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecordingSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "recording_sync_now", ExistingWorkPolicy.REPLACE, request
            )
        }
    }

    override fun doWork(): Result {
        val prefs = CallMonitorApp.prefs
        val employeeId = prefs.getString(CallMonitorApp.KEY_EMPLOYEE_ID, null) ?: return Result.success()
        val deviceUuid = prefs.getString(CallMonitorApp.KEY_DEVICE_UUID, "")!!

        // أول تشغيل: منرجعش لسجل المكالمات كله، بس آخر 24 ساعة كبداية معقولة
        val lastSync = prefs.getLong(KEY_LAST_SYNC, System.currentTimeMillis() - 24 * 60 * 60 * 1000L)

        val calls = CallLogReader.getCallsSince(applicationContext, lastSync)
        val recordings = RecordingFolderScanner.listRecordings(lastSync - MATCH_WINDOW_MS)
        val usedRecordings = mutableSetOf<String>()

        for (call in calls) {
            val callKey = "$KEY_UPLOADED_PREFIX${call.startTimestamp}_${call.phoneNumber}"
            if (prefs.getBoolean(callKey, false)) continue // اترفعت قبل كده

            // نلاقي أقرب ملف تسجيل للمكالمة دي في نافذة زمنية معقولة
            val match = recordings
                .filter { it.file.name !in usedRecordings }
                .minByOrNull { Math.abs(it.parsedTimestamp - call.startTimestamp) }
                ?.takeIf { Math.abs(it.parsedTimestamp - call.startTimestamp) <= MATCH_WINDOW_MS }

            val recordingFileName = match?.file?.name

            runBlocking {
                try {
                    ApiClient.service.uploadCallLog(
                        CallLogEntry(
                            deviceUuid = deviceUuid,
                            employeeId = employeeId,
                            phoneNumber = call.phoneNumber,
                            callType = call.callType,
                            startTimestamp = call.startTimestamp,
                            durationSeconds = call.durationSeconds,
                            recordingFileName = recordingFileName
                        )
                    )
                    prefs.edit().putBoolean(callKey, true).apply()

                    if (match != null) {
                        usedRecordings.add(match.file.name)
                        uploadRecordingFile(match, deviceUuid, employeeId)
                    } else if (call.durationSeconds > 5) {
                        // مكالمة فيها كلام فعلي لكن مفيش تسجيل ليها - نبلغ فورًا،
                        // ممكن يكون الموظف قافل خاصية التسجيل من إعدادات الهاتف
                        reportMissingRecording(deviceUuid, employeeId, call.phoneNumber, call.startTimestamp)
                    }
                } catch (_: Exception) {
                    // هنعيد المحاولة في الدورة الجاية - منعلمش الحدث كمرفوع
                }
            }
        }

        // نحدّث آخر وقت مزامنة لأقدم مكالمة اترفعت في الدفعة دي (أو نفس القديم لو مفيش جديد)
        val newestProcessed = calls.maxOfOrNull { it.startTimestamp }
        if (newestProcessed != null && newestProcessed > lastSync) {
            prefs.edit().putLong(KEY_LAST_SYNC, newestProcessed).apply()
        }

        checkForDeletedRecordings(deviceUuid, employeeId)
        sendHeartbeat(deviceUuid, employeeId)

        return Result.success()
    }

    /**
     * بنقارن كل الملفات اللي احنا نفسنا رفعناها بنجاح قبل كده (متتبعة محليًا)
     * مع اللي لسه موجود فعليًا في مجلدات "call" على الجهاز. أي ملف كان
     * مرفوع واختفى دلوقتي = اتمسح يدويًا، فنبلغ عنه فورًا.
     */
    private fun checkForDeletedRecordings(deviceUuid: String, employeeId: String) {
        val prefs = CallMonitorApp.prefs
        val uploadedFileNames = prefs.all.keys
            .filter { it.startsWith(KEY_RECORDING_UPLOADED_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(KEY_RECORDING_UPLOADED_PREFIX) }

        for (fileName in uploadedFileNames) {
            val reportedKey = "$KEY_DELETION_REPORTED_PREFIX$fileName"
            if (prefs.getBoolean(reportedKey, false)) continue // اتبلّغ عنه قبل كده

            if (!RecordingFolderScanner.fileStillExists(fileName)) {
                runBlocking {
                    try {
                        val resp = ApiClient.service.reportDeletion(
                            DeletionEvent(
                                deviceUuid = deviceUuid,
                                employeeId = employeeId,
                                recordingFileName = fileName,
                                detectedAt = System.currentTimeMillis(),
                                note = "الملف كان مرفوع بنجاح على السيرفر ثم اختفى من الجهاز - على الأغلب اتمسح يدويًا"
                            )
                        )
                        if (resp.isSuccessful) {
                            prefs.edit().putBoolean(reportedKey, true).apply()
                        }
                    } catch (_: Exception) {
                        // هنعيد المحاولة الدورة الجاية
                    }
                }
            }
        }
    }

    private fun sendHeartbeat(deviceUuid: String, employeeId: String) {
        val batteryLevel = getBatteryLevel()
        runBlocking {
            try {
                ApiClient.service.heartbeat(deviceUuid, employeeId, batteryLevel)
            } catch (_: Exception) {
                // مفيش داعي نعيد المحاولة - النبضة الجاية بعد شوية هتغطيها
            }
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    private suspend fun uploadRecordingFile(match: DeviceRecordingFile, deviceUuid: String, employeeId: String) {
        val fileKey = "$KEY_RECORDING_UPLOADED_PREFIX${match.file.name}"
        if (CallMonitorApp.prefs.getBoolean(fileKey, false)) return

        try {
            // نستخدم MIME الصحيح حسب امتداد الملف (m4a/awb/amr/mp3/..إلخ)
            val mime = RecordingFolderScanner.getMimeType(match.file.name)
            val reqFile = match.file.asRequestBody(mime.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", match.file.name, reqFile)
            val resp = ApiClient.service.uploadRecording(
                deviceUuid.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull()),
                employeeId.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull()),
                match.file.name.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull()),
                filePart
            )
            if (resp.isSuccessful) {
                CallMonitorApp.prefs.edit().putBoolean(fileKey, true).apply()
                // ملحوظة: منمسحش ملف سامسونج نفسه من الجهاز - ده ملكه هو مش
                // إحنا، ومسحه ممكن يعتبر تدخل غير مرغوب. بنكتفي بمراقبته.
            }
        } catch (_: Exception) {
            // هيتعاد رفعه في الدورة الجاية لأن fileKey لسه false
        }
    }

    private fun reportMissingRecording(deviceUuid: String, employeeId: String, phoneNumber: String, callTimestamp: Long) {
        runBlocking {
            try {
                ApiClient.service.reportDeletion(
                    DeletionEvent(
                        deviceUuid = deviceUuid,
                        employeeId = employeeId,
                        recordingFileName = "MISSING_${phoneNumber}_$callTimestamp",
                        detectedAt = System.currentTimeMillis(),
                        note = "مكالمة حقيقية بدون تسجيل مقابل لها - تأكد إن خاصية تسجيل المكالمات مفعّلة في إعدادات تطبيق الهاتف"
                    )
                )
            } catch (_: Exception) { }
        }
    }
}
