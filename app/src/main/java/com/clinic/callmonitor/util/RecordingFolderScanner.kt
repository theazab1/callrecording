package com.clinic.callmonitor.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class DeviceRecordingFile(
    val file: File,
    val parsedTimestamp: Long
)

/**
 * يبحث عن ملفات تسجيل المكالمات على أجهزة مختلفة.
 *
 * الاستراتيجية: بحث خفيف بعمق محدود (3 مستويات فقط) + مسارات ثابتة معروفة
 * لأجهزة Samsung وOppo وXiaomi وHuawei.
 *
 * الامتدادات المدعومة:
 *   .m4a  → Samsung, Xiaomi, Huawei
 *   .awb  → Oppo, Realme (AMR-WideBand)
 *   .amr  → أجهزة قديمة / Huawei
 *   .mp3  → بعض الأجهزة
 *   .3gp  → MediaTek القديم
 */
object RecordingFolderScanner {

    private val AUDIO_EXTENSIONS = setOf("m4a", "awb", "amr", "mp3", "3gp", "aac", "wav")

    // ====== المسارات الثابتة المعروفة حسب الماركة ======
    // Samsung:       Recordings/Call  أو  Call recordings
    // Oppo/Realme:   Music/Recordings/Call recordings
    // Xiaomi/MIUI:   MIUI/sound_recorder/call_rec  أو  Recordings/Call recordings
    // Huawei:        Sounds/Recordings  أو  PhoneRecord
    private val KNOWN_PATHS = listOf(
        "Recordings/Call",
        "Recordings/Call recordings",
        "Call recordings",
        "CallRecordings",
        "Music/Recordings/Call recordings",
        "Music/Recordings",
        "MIUI/sound_recorder/call_rec",
        "Recorder/call",
        "Sounds/Recordings",
        "PhoneRecord",
        "Records"
    )

    // ====== كلمات دالة على مجلدات مكالمات (للبحث الديناميكي السريع) ======
    private val CALL_KEYWORDS = listOf("call", "record", "voic", "phone")

    // مجلدات نتجاهلها تماماً لتسريع البحث
    private val SKIP_DIRS = setOf(
        "Android", "WhatsApp", "Telegram", "Signal", "DCIM",
        "Pictures", "Movies", "Video", "Download",
        "Alarms", "Notifications", "Ringtones", ".thumbnails"
    )

    // ====== صيغ التاريخ في أسماء الملفات ======
    private data class TsPattern(val regex: Regex, val fmt: SimpleDateFormat)

    private val TS_PATTERNS = listOf(
        // Samsung/Xiaomi:   260919_143022
        TsPattern(Regex("""(\d{6}_\d{6})"""),           SimpleDateFormat("yyMMdd_HHmmss", Locale.US)),
        // Generic:          20260919_143022
        TsPattern(Regex("""(\d{8}_\d{6})"""),           SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)),
        // Oppo 12 digits:   260920211305  (ddMMyyyyHHmm)
        TsPattern(Regex("""^(\d{12})"""),               SimpleDateFormat("ddMMyyyyHHmm", Locale.US)),
        // Oppo 10 digits:   2609202113    (ddMMyyyyHH)
        TsPattern(Regex("""^(\d{10})"""),               SimpleDateFormat("ddMMyyyyHH", Locale.US)),
        // Huawei:           2026-09-19-14-30-22
        TsPattern(Regex("""(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})"""), SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US))
    )

    // ==================== واجهة عامة ====================

    fun findAllCallFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val found = mutableListOf<File>()
        val seenPaths = mutableSetOf<String>()

        // 1. المسارات الثابتة أولاً (سريعة ومضمونة)
        for (rel in KNOWN_PATHS) {
            val dir = File(root, rel)
            if (dir.isDirectory && seenPaths.add(dir.canonicalPath)) {
                found.add(dir)
            }
        }

        // 2. بحث ديناميكي خفيف بعمق 3 مستويات فقط (للأجهزة غير المعروفة)
        scanLight(root, 0, found, seenPaths)

        return found
    }

    private fun scanLight(dir: File, depth: Int, found: MutableList<File>, seen: MutableSet<String>) {
        if (depth > 3) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name in SKIP_DIRS || child.name.startsWith(".")) continue
            val lower = child.name.lowercase()
            if (CALL_KEYWORDS.any { lower.contains(it) }) {
                val canon = try { child.canonicalPath } catch (_: Exception) { child.absolutePath }
                if (seen.add(canon)) found.add(child)
            }
            scanLight(child, depth + 1, found, seen)
        }
    }

    /** كل ملفات الصوت بعد timestamp معيّن */
    fun listRecordings(afterTimestamp: Long): List<DeviceRecordingFile> {
        val folders = findAllCallFolders()
        val results = mutableListOf<DeviceRecordingFile>()
        val seenFiles = mutableSetOf<String>()

        for (folder in folders) {
            val files = try {
                folder.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS }
            } catch (_: Exception) { null } ?: continue

            for (file in files) {
                val canon = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
                if (!seenFiles.add(canon)) continue
                val ts = parseTimestamp(file.name) ?: file.lastModified()
                if (ts > afterTimestamp) results.add(DeviceRecordingFile(file, ts))
            }
        }
        return results.sortedBy { it.parsedTimestamp }
    }

    fun fileStillExists(fileName: String): Boolean {
        for (folder in findAllCallFolders()) {
            if (File(folder, fileName).exists()) return true
        }
        return false
    }

    fun getMimeType(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "awb"        -> "audio/amr-wb"
        "amr"        -> "audio/amr"
        "3gp"        -> "audio/3gpp"
        "mp3"        -> "audio/mpeg"
        "aac"        -> "audio/aac"
        "wav"        -> "audio/wav"
        else         -> "audio/mp4"
    }

    fun parseTimestamp(name: String): Long? {
        for (p in TS_PATTERNS) {
            val m = p.regex.find(name) ?: continue
            return try { p.fmt.parse(m.groupValues[1])?.time } catch (_: Exception) { null }
        }
        return null
    }
}
