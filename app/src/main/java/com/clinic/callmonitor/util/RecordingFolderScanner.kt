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
 * بدل ما نحط قائمة مسارات ثابتة (اللي بتختلف من سامسونج لأوبو لشاومي..إلخ)،
 * بندوّر تلقائيًا على أي مجلد اسمه فيه كلمة "call" جوه التخزين، ونجمع كل
 * الملفات الصوتية اللي فيه. ده بيشتغل على أغلب الماركات من غير ما نعرف
 * مقدمًا شكل المجلد بالظبط.
 */
object RecordingFolderScanner {

    private const val MAX_DEPTH = 4
    private val AUDIO_EXTENSIONS = setOf("m4a", "amr", "3gp", "mp3", "wav", "aac")

    // بعض المجلدات معروف إنها مالهاش لازمة (تبطئ البحث من غير فايدة)
    private val SKIP_DIR_NAMES = setOf(
        "Android", "WhatsApp", "Telegram", "DCIM", "Pictures", "Movies",
        ".thumbnails", "cache", "Download"
    )

    private val TIMESTAMP_REGEX = Regex("""(\d{6}_\d{6})""")
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyMMdd_HHmmss", Locale.US)

    /** بيرجع كل المجلدات اللي اسمها فيه "call" (زي Recordings/Call أو Call Records) */
    fun findAllCallFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val found = mutableListOf<File>()
        searchRecursive(root, 0, found)
        return found
    }

    private fun searchRecursive(dir: File, depth: Int, found: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return

        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name in SKIP_DIR_NAMES) continue
            if (child.name.startsWith(".")) continue

            if (child.name.contains("call", ignoreCase = true)) {
                found.add(child)
                // مش لازم نكمل نبحث جوه المجلد ده تاني، هو نفسه المطلوب
                continue
            }
            searchRecursive(child, depth + 1, found)
        }
    }

    /** كل ملفات الصوت من كل مجلدات "call" الموجودة، بعد وقت معيّن */
    fun listRecordings(afterTimestamp: Long): List<DeviceRecordingFile> {
        val folders = findAllCallFolders()
        val results = mutableListOf<DeviceRecordingFile>()

        for (folder in folders) {
            val files = folder.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS }
                ?: continue
            for (file in files) {
                val ts = parseTimestampFromName(file.name) ?: file.lastModified()
                if (ts > afterTimestamp) results.add(DeviceRecordingFile(file, ts))
            }
        }
        return results.sortedBy { it.parsedTimestamp }
    }

    /** هل فيه ملف بنفس الاسم ده لسه موجود في أي مجلد "call"؟ - مستخدمة لفحص الحذف */
    fun fileStillExists(fileName: String): Boolean {
        val folders = findAllCallFolders()
        for (folder in folders) {
            if (File(folder, fileName).exists()) return true
        }
        return false
    }

    private fun parseTimestampFromName(name: String): Long? {
        val match = TIMESTAMP_REGEX.find(name) ?: return null
        return try {
            TIMESTAMP_FORMAT.parse(match.groupValues[1])?.time
        } catch (e: Exception) {
            null
        }
    }
}
