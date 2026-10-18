package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import java.util.Random
import java.util.zip.*

class ModelPackageInstallerTest {
    @get:Rule val temp=TemporaryFolder()
    private val raw=ByteArray(256*1024).apply {Random(42).nextBytes(this)}
    private val gz=ByteArrayOutputStream().also {out -> GZIPOutputStream(out).use {it.write(raw)}}.toByteArray()
    private val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {"%02x".format(it)}
    private val asset=TranslationModelFile("model","model.bin",raw.size.toLong(),hash,"model.gz","model.gz",gz.size.toLong(),hash)
    private val pack=TranslationModelPack("test-en","1",LocalTranslationLanguage.JAPANESE,LocalTranslationLanguage.ENGLISH,listOf(asset))
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private val requests=mutableListOf<Pair<String?,String?>>()
    private var mode="normal"
    private var payload=gz
    private val url get()=server.url("/model.gz").toString()
    @Before fun setup() {
        root=temp.newFolder("models")
        server=MockWebServer()
        server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val range=request.getHeader("Range")
            requests.add(range to request.getHeader("If-Range"))
            val offset=if(mode=="ignore") 0 else range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val response=MockResponse().addHeader("ETag",if(mode=="etag-changed") "\"v2\"" else "\"v1\"")
            if(offset>=payload.size) {
                return response.addHeader("Content-Range","bytes */${payload.size}").setResponseCode(416)
            } else {
                val status=if(range!=null && mode!="ignore") 206 else 200
                if(status==206) response.addHeader("Content-Range","bytes ${if(mode=="wrong") offset+1 else offset}-${payload.size-1}/${payload.size}")
                return response.setResponseCode(status).setBody(Buffer().write(payload,offset,payload.size-offset))
            }
        } };server.start()
    }
    @After fun close() {server.shutdown()}
    private fun installer()=ModelPackageInstaller(root)
    @Test fun obsoleteDescriptorsAreIgnoredWithoutHidingUsablePackages() {
        val current = TranslationModelCatalog(File("src/main/assets/translation/catalog.json").readText()).packs.first()
        val descriptor = TranslationModelCatalog.packJson(current)
        File(installer().installedDirectory(current).apply { mkdirs() }, "package.json").writeText(
            JsonObject(descriptor + ("engine" to JsonPrimitive("BERGAMOT"))).toString())
        val obsolete = current.copy(id = "obsolete-package")
        File(installer().installedDirectory(obsolete).apply { mkdirs() }, "package.json").writeText(
            JsonObject(TranslationModelCatalog.packJson(obsolete) + ("engine" to JsonPrimitive("OBSOLETE_BACKEND"))).toString())
        assertEquals(listOf(current), installer().knownPackages())
    }
    private suspend fun download(installer: ModelPackageInstaller=installer())=installer.download(pack,{url},{})
    private suspend fun partial() {
        try {installer().download(pack,{url},{if(it.completedBytes>65536 && it.status==TranslationPackStatus.DOWNLOADING) throw CancellationException("pause")})}
        catch(_:CancellationException) { }
        assertFalse(installer().isInstalled(pack));assertTrue(installer().hasPartial(pack))
    }
    @Test fun installsAllOrNothingAndVerifiesAfterReload() = runBlocking {
        download();assertTrue(installer().isInstalled(pack));installer().verifyInstalled(pack)
        assertArrayEquals(raw,File(installer().installedDirectory(pack),asset.name).readBytes())
    }
    @Test fun corruptedContentCannotBeInstalled() = runBlocking {
        val bad=raw.clone().apply {this[7]=0}
        payload=ByteArrayOutputStream().also {o->GZIPOutputStream(o).use {it.write(bad)}}.toByteArray()
        try {download();fail("Must reject bad hash")}catch(_:IllegalArgumentException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun truncatedGzipCannotBeInstalled() = runBlocking {
        payload=gz.copyOf(gz.size/2)
        try {download();fail("Must reject truncated gzip")}catch(_:IOException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun processRestartResumesRangeAndStrongValidator() = runBlocking {
        partial();download()
        assertTrue(requests.last().first?.startsWith("bytes=")==true)
        assertEquals("\"v1\"",requests.last().second);assertTrue(installer().isInstalled(pack))
    }
    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() = runBlocking {
        partial();mode="ignore";download();assertTrue(installer().isInstalled(pack))
    }
    @Test fun wrongRangeIsRejected() = runBlocking {
        partial();mode="wrong"
        try {download();fail("Must reject wrong range")}catch(_:IllegalArgumentException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun changingSourceRestartsCompressedStream() = runBlocking {
        partial();installer().download(pack,{url+"?new-source=true"},{})
        assertNull(requests.last().first);assertTrue(installer().isInstalled(pack))
    }
    @Test fun changedEtagOnPartialResponseIsRejected() = runBlocking {
        partial();mode="etag-changed"
        try {download();fail("Must reject changed validator")}catch(_:IllegalArgumentException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun completePartialGzipCanResumeAfterKillBeforeVerification() = runBlocking {
        val dir=File(root,"test-en-1.staging").apply {mkdirs()}
        File(dir,"model.bin.part").writeBytes(gz);File(dir,"model.bin.source").writeText(url)
        download();assertTrue(installer().isInstalled(pack))
    }
    private fun zip(vararg entries: Pair<String,ByteArray>)=ByteArrayOutputStream().also {o->ZipOutputStream(o).use {zip->
        entries.forEach {(name,data)->zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}
    }}.toByteArray().inputStream()
    @Test fun zipImportMatchesKnownRawFiles() = runBlocking {
        installer().importZip(pack,zip("model.bin" to raw));assertTrue(installer().isInstalled(pack))
    }
    @Test fun zipTraversalCannotEscapeStaging() = runBlocking {
        try {installer().importZip(pack,zip("../escape.bin" to raw));fail("Must reject traversal")}catch(_:IllegalStateException) { }
        assertFalse(File(root,"escape.bin").exists());assertFalse(installer().isInstalled(pack))
    }
    @Test fun zipMissingFilesIsRejected() = runBlocking {
        try {installer().importZip(pack,zip());fail("Must reject missing files")}catch(_:IllegalArgumentException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun zipUnknownFileIsRejected() = runBlocking {
        try {installer().importZip(pack,zip("script.yml" to byteArrayOf(1)));fail("Must reject unknown file")}
        catch(_:IllegalStateException) { }
    }
    @Test fun zipDuplicateRawFileIsRejected() = runBlocking {
        val bytes=zip("model.bin" to raw,"xodel.bin" to raw).readBytes()
        val needle="xodel.bin".toByteArray()
        for(i in 0..bytes.size-needle.size) if(needle.indices.all {bytes[i+it]==needle[it]}) bytes[i]='m'.code.toByte()
        try {installer().importZip(pack,bytes.inputStream());fail("Must reject duplicate file")}
        catch(_:IllegalArgumentException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun zipExpansionBeyondDeclaredSizeIsRejected() = runBlocking {
        try {installer().importZip(pack,zip("model.bin" to (raw+byteArrayOf(1))));fail("Must reject oversized file")}catch(_:IllegalArgumentException) { }
        assertFalse(installer().isInstalled(pack))
    }
    @Test fun modifiedInstalledFileFailsReverification() = runBlocking {
        download();File(installer().installedDirectory(pack),asset.name).writeBytes(raw.clone().apply {this[0]=1})
        try {installer().verifyInstalled(pack);fail("Must reject modification")}catch(_:IllegalArgumentException) { }
    }
    @Test fun cancellingDownloadOnlyRemovesComponentStaging() = runBlocking {
        partial();val untouched=File(root,"user-file.txt").apply {writeText("keep")}
        installer().discardPartial(pack);assertFalse(installer().hasPartial(pack));assertEquals("keep",untouched.readText())
    }
    @Test fun unsafePackageIdentifiersAndFileNamesAreRejectedBeforeFileAccess() {
        assertThrows(IllegalArgumentException::class.java) {installer().remove(pack.copy(id="../escape"))}
        assertThrows(IllegalArgumentException::class.java) {installer().remove(pack.copy(files=listOf(asset.copy(name="../escape.bin"))))}
    }
    @Test fun uncompressedRemoteAttachmentsUseTheSameAtomicHashVerification()=runBlocking {
        payload=raw
        val p=pack.copy(version="2.1",files=listOf(asset.copy(compression=TranslationFileCompression.NONE,downloadSize=raw.size.toLong())))
        installer().download(p,{url},{});assertTrue(installer().isInstalled(p));installer().verifyInstalled(p)
    }
    @Test fun installedDescriptorSurvivesCatalogChangeAndDeleteAllowsNewVersion()=runBlocking {
        val p=pack.copy(version="2.1",files=listOf(asset,asset.copy(role="lexicalShortlist",name="lex.bin"),asset.copy(role="vocab",name="vocab.spm")))
        installer().download(p,{url},{})
        val newer=p.copy(version="2.2")
        assertEquals(listOf(p),installer().knownPackages(listOf(newer)))
        assertFalse(installer().isInstalled(newer));assertTrue(installer().isInstalled(p))
        installer().remove(p);installer().download(newer,{url},{})
        assertEquals(listOf(newer),installer().knownPackages())
    }
    @Test fun dottedVersionsDoNotAllowPathTraversal() {
        listOf("../escape","2/1","2..1",".staging").forEach {version ->assertThrows(IllegalArgumentException::class.java) {installer().remove(pack.copy(version=version))}}
    }
}
