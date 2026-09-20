package com.clinic.callmonitor.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.clinic.callmonitor.R

/**
 * ملحوظة مهمة: التطبيق ده بقى مايسجلش صوت بنفسه خالص. التسجيل الفعلي بيتم
 * من تطبيق الهاتف الافتراضي في سامسونج (اللي عنده صلاحيات نظام مش متاحة
 * لينا كتطبيق عادي). دورنا هنا بقى:
 *   1) إظهار تذكير للموظف يقول جملة التنبيه الصوتي للعميل.
 *   2) بعد إغلاق المكالمة، نشغّل RecordingSyncWorker اللي بيراقب مجلد
 *      تسجيلات سامسونج + سجل المكالمات، ويربط ويرفع الاثنين (شرح كامل في
 *      RecordingSyncWorker.kt).
 * لو الجهاز مش سامسونج أو مفيش مجلد تسجيلات، السجل (رقم/وقت/مدة) لسه
 * هيترفع عادي، بس من غير ملف صوتي (لأنه أصلاً مش موجود على الجهاز).
 */
class CallMonitorService : Service() {

    companion object {
        const val ACTION_CALL_STARTED = "ACTION_CALL_STARTED"
        const val ACTION_CALL_ENDED = "ACTION_CALL_ENDED"
        const val EXTRA_PHONE_NUMBER = "EXTRA_PHONE_NUMBER"
        const val NOTIF_CHANNEL_ID = "call_monitor_channel"
        const val NOTIF_ID = 101
    }

    private var reminderView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("جاري تشغيل خدمة مراقبة المكالمات"))

        when (intent?.action) {
            ACTION_CALL_STARTED -> {
                showReminderOverlay()
            }
            ACTION_CALL_ENDED -> {
                hideReminderOverlay()
                // نديله 20 ثانية تأخير (متضمنة جوه Worker نفسه) عشان سامسونج
                // يخلص يكتب ملف الـ m4a بالكامل قبل ما نحاول نلاقيه ونرفعه
                RecordingSyncWorker.scheduleOneTimeAfterCall(applicationContext)
            }
        }
        return START_STICKY
    }

    private fun showReminderOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        // مسح أي رسالة تذكير سابقة عشان ما تتراكمش فوق بعض لو استلمنا
        // أحداث متتالية (مثل RINGING ثم OFFHOOK)
        hideReminderOverlay()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        reminderView = TextView(this).apply {
            text = "ذكّر العميل: \"هذه المكالمة قد تُسجل لأغراض الجودة\""
            setBackgroundColor(0xCC222222.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 16, 24, 16)
            textSize = 14f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 100
        }
        try {
            wm.addView(reminderView, params)
        } catch (_: Exception) { }
    }

    private fun hideReminderOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        reminderView?.let {
            try { wm.removeView(it) } catch (_: Exception) { }
        }
        reminderView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "مراقبة المكالمات",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("تطبيق العيادة نشط")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
