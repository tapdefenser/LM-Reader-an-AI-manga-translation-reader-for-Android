package com.lmreader.ui.settings.backup

import com.lmreader.ui.queue.ExportFiles
import java.io.*
import java.util.Properties
import java.util.zip.*

/** A closed allowlist, bounded expansion and a hash inventory protect the restore boundary. */
object BackupArchive {
    const val MAX_ENTRY = 64_000_000L
    const val MAX_TOTAL = 512_000_000L
    private const val MANIFEST = "manifest.properties"
    val tables = listOf("library_sources", "mangas", "chapters", "categories", "metadata_records",
        "shelf_entries", "reading_progress", "chapter_read_state", "chapter_translation", "manga_glossary")
    private val requiredDocuments = setOf("preferences.json", "api.json", "settings.json", "workflows.json", "queue-order.json")
    /** New media/task section; absent in old archives, which then keep all local media/task data. */
    private val optionalDocuments = setOf("media-work.json")
    private val documents = requiredDocuments + optionalDocuments
    fun allowed(name: String) = name in documents || tables.any { name == "database/$it.json" } ||
        Regex("pages/[a-f0-9]{64}\\.json").matches(name) || name == "video-library.json" ||
        Regex("videos/[a-f0-9-]{36}\\.json").matches(name) ||
        Regex("videos/[a-f0-9-]{36}/(frames|proofs|sources)/[a-f0-9]{64}\\.json").matches(name)

    fun write(root: File, output: OutputStream) {
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        require(files.size <= 100_000 && files.sumOf { it.length() } <= MAX_TOTAL)
        val manifest = Properties().apply { setProperty("format", "LM-Reader"); setProperty("version", "1") }
        files.forEach { file ->
            val name = file.relativeTo(root).invariantSeparatorsPath
            require(allowed(name) && file.length() <= MAX_ENTRY)
            manifest.setProperty("sha256.$name", ExportFiles.hash(file)); manifest.setProperty("size.$name", file.length().toString())
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); manifest.store(zip, "LM-Reader backup inventory"); zip.closeEntry()
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    fun read(input: InputStream, root: File) {
        check(root.mkdirs() || root.isDirectory)
        val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && (name == MANIFEST || allowed(name)) && seen.add(name) && seen.size <= 100_001) { "备份包含无效或重复的文件" }
                val target = File(root, name)
                require(target.canonicalPath.startsWith(root.canonicalPath + File.separator))
                target.parentFile!!.mkdirs()
                val limit = if (name == MANIFEST) 16_000_000L else MAX_ENTRY
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) { val count = zip.read(buffer); if (count < 0) break
                        size += count; total += count
                        require(size <= limit && total <= MAX_TOTAL) { "备份展开后超过大小上限" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                zip.closeEntry()
            }
        }
        val manifest = Properties().apply { File(root, MANIFEST).inputStream().use { load(it) } }
        require(manifest.getProperty("format") == "LM-Reader" && manifest.getProperty("version") == "1") { "不支持此备份版本" }
        val expected = manifest.stringPropertyNames().filter { it.startsWith("sha256.") }.map { it.removePrefix("sha256.") }.toSet()
        require(expected + MANIFEST == seen && requiredDocuments.all { it in expected } &&
            tables.all { "database/$it.json" in expected }) { "备份清单不完整" }
        expected.forEach { name ->
            val file = File(root, name)
            require(manifest.getProperty("size.$name") == file.length().toString() && manifest.getProperty("sha256.$name") == ExportFiles.hash(file)) { "备份校验失败：$name" }
        }
        File(root, MANIFEST).delete()
    }
}
