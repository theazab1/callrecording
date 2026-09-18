package com.clinic.callmonitor.ui

import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.clinic.callmonitor.CallMonitorApp
import com.clinic.callmonitor.R
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.clinic.callmonitor.network.ApiClient
import com.clinic.callmonitor.network.CallLogEntry
import com.clinic.callmonitor.util.RecordingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * شاشة الموظف البسيطة التي تعرض حالته وتسمح له بإدخال التوكن
 * الذي تم إصداره من السيرفر، مع أداة تشخيص سريعة لمشاكل الصوت.
 */
class MainActivity : AppCompatActivity() {

    private var testRecorder: MediaRecorder? = null
    private var testFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val name = CallMonitorApp.prefs.getString(CallMonitorApp.KEY_EMPLOYEE_NAME, "")
        val currentToken = CallMonitorApp.prefs.getString(CallMonitorApp.KEY_API_TOKEN, "")

        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val etApiToken = findViewById<EditText>(R.id.etApiToken)
        val btnSaveToken = findViewById<Button>(R.id.btnSaveToken)
        val btnTestRecording = findViewById<Button>(R.id.btnTestRecording)
        val tvTestStatus = findViewById<TextView>(R.id.tvTestStatus)

        tvStatus.text = "مرحبًا $name\nالتطبيق يعمل في الخلفية لمراقبة الجودة."

        if (!currentToken.isNullOrEmpty()) {
            etApiToken.setText(currentToken)
        }

        btnSaveToken.setOnClickListener {
            val newToken = etApiToken.text.toString().trim()
            if (newToken.isNotEmpty()) {
                CallMonitorApp.prefs.edit()
                    .putString(CallMonitorApp.KEY_API_TOKEN, newToken)
                    .apply()
                Toast.makeText(this, "تم حفظ التوكن بنجاح!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "يرجى إدخال التوكن أولاً", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestRecording.setOnClickListener {
            runDiagnosticTestRecording(btnTestRecording, tvTestStatus)
        }

        val btnSyncNow = findViewById<Button>(R.id.btnSyncNow)
        btnSyncNow.setOnClickListener {
            com.clinic.callmonitor.service.RecordingSyncWorker.scheduleNow(applicationContext)
            Toast.makeText(this, "بدأت المزامنة في الخلفية - افتح لوحة الأدمن بعد دقيقة", Toast.LENGTH_LONG).show()
        }
    }

    private fun runDiagnosticTestRecording(button: Button, statusView: TextView) {
        button.isEnabled = false
        statusView.text = "جاري التسجيل... اتكلم دلوقتي بصوت عادي (5 ثواني)"

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val employeeId = CallMonitorApp.prefs.getString(CallMonitorApp.KEY_EMPLOYEE_ID, "unknown")
        val fileName = "test_${employeeId}_$ts.m4a"
        testFile = File(RecordingStore.getRecordingsDir(this), fileName)
        val startTime = System.currentTimeMillis()

        try {
            testRecorder = MediaRecorder().apply {
                // مصدر MIC عادي هنا (مش وقت مكالمة) - ده أبسط مصدر ممكن ومفروض
                // يشتغل 100% لو مشكلة الصلاحيات أو الترميز مش موجودة
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(testFile!!.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            statusView.text = "فشل حتى التسجيل العادي: ${e.message}\nده معناه مشكلة صلاحيات RECORD_AUDIO مش قيد مكالمات"
            button.isEnabled = true
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                testRecorder?.stop()
            } catch (_: Exception) { }
            testRecorder?.release()
            testRecorder = null

            val file = testFile
            if (file == null || !file.exists() || file.length() < 500) {
                statusView.text = "الملف فاضي أو صغير جدًا (${file?.length() ?: 0} بايت) - في مشكلة حتى في التسجيل العادي!"
                button.isEnabled = true
                return@postDelayed
            }

            statusView.text = "تم التسجيل (${file.length()} بايت). جاري الرفع..."
            uploadTestRecording(file, startTime, button, statusView)
        }, 5000)
    }

    private fun uploadTestRecording(file: File, startTime: Long, button: Button, statusView: TextView) {
        val employeeId = CallMonitorApp.prefs.getString(CallMonitorApp.KEY_EMPLOYEE_ID, "unknown")!!
        val deviceUuid = CallMonitorApp.prefs.getString(CallMonitorApp.KEY_DEVICE_UUID, "")!!

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            var errorMsg = ""
            try {
                ApiClient.service.uploadCallLog(
                    CallLogEntry(
                        deviceUuid = deviceUuid,
                        employeeId = employeeId,
                        phoneNumber = "TEST-DIAGNOSTIC",
                        callType = "TEST",
                        startTimestamp = startTime,
                        durationSeconds = 5,
                        recordingFileName = file.name
                    )
                )

                val reqFile = file.asRequestBody("audio/mp4".toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", file.name, reqFile)
                val resp = ApiClient.service.uploadRecording(
                    deviceUuid.toRequestBody("text/plain".toMediaTypeOrNull()),
                    employeeId.toRequestBody("text/plain".toMediaTypeOrNull()),
                    file.name.toRequestBody("text/plain".toMediaTypeOrNull()),
                    filePart
                )
                success = resp.isSuccessful
                if (!success) errorMsg = "كود الخطأ: ${resp.code()}"
            } catch (e: Exception) {
                errorMsg = e.message ?: "خطأ غير معروف في الاتصال"
            }

            withContext(Dispatchers.Main) {
                statusView.text = if (success)
                    "تم الرفع بنجاح ✅ روح لوحة الأدمن ودوّر على مكالمة برقم TEST-DIAGNOSTIC واسمعها"
                else
                    "فشل الرفع على السيرفر: $errorMsg\n(الملف اتسجل صح محليًا، المشكلة في الاتصال بالسيرفر أو التوكن)"
                button.isEnabled = true
            }
        }
    }
}

