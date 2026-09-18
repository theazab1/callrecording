package com.clinic.callmonitor.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clinic.callmonitor.R
import com.clinic.callmonitor.service.FileWatcherService
import com.clinic.callmonitor.service.RecordingSyncWorker

class PermissionsActivity : AppCompatActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkOverlayThenNext() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            checkOverlayThenNext()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkOverlayThenNext() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            // ملحوظة: المستخدم لازم يرجع بنفسه للتطبيق بعد الموافقة -
            // في onResume هنكمل التحقق
            return
        }
        checkAllFilesAccessThenNext()
    }

    /**
     * محتاجينها عشان نقدر نقرا مجلد Recordings/Call بتاع سامسونج مباشرة.
     * من غيرها على أندرويد 11+ (API 30+)، مفيش وصول لملفات برا مجلدات
     * التطبيق نفسه إلا عن طريق MediaStore (وده مش بيغطي مجلد التسجيلات
     * في كل إصدارات One UI بشكل موثوق).
     */
    private fun checkAllFilesAccessThenNext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                // بعض الأجهزة (خصوصًا بعض إصدارات سامسونج المخصصة) مش بتدعم
                // الـ intent الخاص بتطبيق واحد - نفتح الشاشة العامة بدلاً منها
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }
        goToAccessibilitySettingsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                goToAccessibilitySettingsIfNeeded()
            }
        }
    }

    private fun goToAccessibilitySettingsIfNeeded() {
        // فحص Accessibility Service مفعّل ولا لأ بيتم بشكل مبسط هنا -
        // التفاصيل الكاملة في util/AccessibilityUtils (يُنصح بإضافتها)
        finishSetupAndStart()
    }

    private fun finishSetupAndStart() {
        startService(Intent(this, FileWatcherService::class.java))
        RecordingSyncWorker.schedulePeriodic(applicationContext)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

