package com.clinic.callmonitor.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class DeviceRecordingFile(
    val file: File,
    val parsedTimestamp: Long // بالميلي ثانية - من اسم الملف لو أمكن، وإلا من تاريخ التعديل
)

/**
 * يبحث تلقائياً عن ملفات تسجيل المكالمات على أجهزة مختلفة:
 *
 * ┌─────────────┬─────────────────────────────────────────────┬─────────────────────────┐
 * │  الماركة    │  مسار التسجيل                               │  الامتداد               │
 * ├─────────────┼─────────────────────────────────────────────┼─────────────────────────┤
 * │  Samsung    │  Recordings/Call  أو  Call Records          │  .m4a                   │
 * │  Oppo/Realme│  Music/Recordings/Call recordings           │  .awb أو .mp3           │
 * │  Xiaomi     │  MIUI/sound_recorder/call_rec               │  .amr أو .m4a           │
 * │  Huawei     │  Sounds/Recordings  أو  PhoneRecord         │  .amr أو .m4a           │
 * └─────────────┴─────────────────────────────────────────────┴─────────────────────────┘
 *
 * الاستراتيجية: بدلاً من تثبيت مسارات جامدة، البحث يمر على كل المجلدات بعمق محدود
 * ويجمع كل المجلدات التي اسمها يُشير للمكالمات، ثم يأخذ الملفات الصوتية منها.
 */
object RecordingFolderScanner {

    private const val MAX_DEPTH = 6 // زيادة العمق لتغطية Oppo: Music/Recordings/Call recordings

    // كل امتدادات التسجيل المعروفة عبر ماركات مختلفة
    private val AUDIO_EXTENSIONS = setOf(
        "m4a",  // Samsung, Xiaomi, Huawei
        "awb",  // Oppo, Realme (Adaptive Multi-Rate WideBand)
        "amr",  // قديم / Huawei / Xiaomi
        "3gp",  // قديم / بعض أجهزة MediaTek
        "mp3",  // نادر لكن موجود في بعض ROM مخصصة
        "wav",  // نادر
        "aac",  // بعض أجهزة Vivo
        "ogg"   // نادر
    )

    // مجلدات نتجاهلها تماماً لتسريع البحث
    private val SKIP_DIR_NAMES = setOf(
        "Android", "WhatsApp", "Telegram", "Signal",
        "DCIM", "Pictures", "Movies", "Video",
        ".thumbnails", "cache", ".cache",
        "Download", "Alarms", "Notifications", "Ringtones"
    )

    // كلمات مفتاحية تدل على مجلدات التسجيل (عربي + إنجليزي)
    // Oppo: "call recordings", Samsung: "call", Xiaomi: "call_rec", Huawei: "phonerecord"
    private val CALL_FOLDER_KEYWORDS = listOf(
        "call rec",   // "Call recordings" / "call_rec"
        "callrec",
        "call",       // Samsung: "Call" / "Calls"
        "phonerecord",// Huawei
        "voicecall",
        "تسجيل"       // بعض الأجهزة العربية
    )

    // صيغ التاريخ المختلفة لأجهزة مختلفة
    // Samsung:  yyMMdd_HHmmss  → "260919_143022"
    // Oppo:     ddMMyyyyHHmm   → "2609202113"   (بدون فاصل)
    private data class TimestampPattern(
        val regex: Regex,
        val format: SimpleDateFormat
    )

    private val TIMESTAMP_PATTERNS = listOf(
        // Samsung & Xiaomi: 260919_143022
        TimestampPattern(
            Regex("""(\d{6}_\d{6})"""),
            SimpleDateFormat("yyMMdd_HHmmss", Locale.US)
        ),
        // Oppo/Realme: ddMMyyyyHHmm → 2609202113xx (12 رقم)
        TimestampPattern(
            Regex("""^(\d{12})"""),
            SimpleDateFormat("ddMMyyyyHHmm", Locale.US)
        ),
        // Oppo/Realme: بعض الإصدارات 10 أرقام ddMMyyyyHH
        TimestampPattern(
            Regex("""^(\d{10})"""),
            SimpleDateFormat("ddMMyyyyHH", Locale.US)
        ),
        // Huawei: yyyy-MM-dd-HH-mm-ss
        TimestampPattern(
            Regex("""(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})"""),
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
        ),
        // Generic: yyyyMMdd_HHmmss
        TimestampPattern(
            Regex("""(\d{8}_\d{6})"""),
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        )
    )

    // ==================== واجهة عامة ====================

    /** بيرجع كل مجلدات التسجيل المكتشفة على الجهاز */
    fun findAllCallFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val found = mutableListOf<File>()

        // 1. البحث الديناميكي (يعمل على أي جهاز)
        searchRecursive(root, 0, found)

        // 2. مسارات ثابتة معروفة كشبكة أمان إضافية
        val knownPaths = listOf(
            // Samsung
            "Recordings/Call",
            "Call recordings",
            "CallRecordings",
            // Oppo / Realme
            "Music/Recordings/Call recordings",
            "Recordings/Call recordings",
            // Xiaomi / MIUI
            "MIUI/sound_recorder/call_rec",
            "Recorder/call",
            // Huawei
            "Sounds/Recordings",
            "PhoneRecord",
            // Vivo
            "Sounds"
        )
        for (rel in knownPaths) {
            val dir = File(root, rel)
            if (dir.isDirectory && dir !in found) {
                found.add(dir)
            }
        }

        return found.distinct()
    }

    private fun searchRecursive(dir: File, depth: Int, found: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return

        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name in SKIP_DIR_NAMES) continue
            if (child.name.startsWith(".")) continue

            val nameLower = child.name.lowercase()
            val isCallFolder = CALL_FOLDER_KEYWORDS.any { nameLower.contains(it) }

            if (isCallFolder) {
                found.add(child)
                // نكمل البحث جوه المجلد ده أيضاً لأن Oppo مثلاً فيه:
                // Music → Recordings → Call recordings (ثلاث مستويات)
                searchRecursive(child, depth + 1, found)
            } else {
                searchRecursive(child, depth + 1, found)
            }
        }
    }

    // ==================== جلب الملفات ====================

    /** كل ملفات الصوت من كل مجلدات التسجيل، بعد وقت معيّن */
    fun listRecordings(afterTimestamp: Long): List<DeviceRecordingFile> {
        val folders = findAllCallFolders()
        val results = mutableListOf<DeviceRecordingFile>()
        val seenPaths = mutableSetOf<String>()

        for (folder in folders) {
            val files = try {
                folder.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS }
            } catch (_: Exception) { null } ?: continue

            for (file in files) {
                // تجنب إضافة نفس الملف أكثر من مرة لو مجلدات متداخلة
                if (!seenPaths.add(file.absolutePath)) continue

                val ts = parseTimestampFromName(file.name) ?: file.lastModified()
                if (ts > afterTimestamp) {
                    results.add(DeviceRecordingFile(file, ts))
                }
            }
        }
        return results.sortedBy { it.parsedTimestamp }
    }

    /** هل فيه ملف بنفس الاسم لسه موجود على الجهاز؟ */
    fun fileStillExists(fileName: String): Boolean {
        val folders = findAllCallFolders()
        for (folder in folders) {
            if (File(folder, fileName).exists()) return true
        }
        return false
    }

    // ==================== تحليل التاريخ ====================

    fun parseTimestampFromName(name: String): Long? {
        for (pattern in TIMESTAMP_PATTERNS) {
            val match = pattern.regex.find(name) ?: continue
            return try {
                pattern.format.parse(match.groupValues[1])?.time
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /** يرجع نوع MIME الصحيح حسب امتداد الملف - مهم لرفع الملف بالشكل الصحيح */
    fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.').lowercase()) {
            "m4a"  -> "audio/mp4"
            "awb"  -> "audio/amr-wb"  // Oppo AWB = AMR-WideBand
            "amr"  -> "audio/amr"
            "3gp"  -> "audio/3gpp"
            "mp3"  -> "audio/mpeg"
            "wav"  -> "audio/wav"
            "aac"  -> "audio/aac"
            "ogg"  -> "audio/ogg"
            else   -> "audio/mp4"
        }
    }
}
