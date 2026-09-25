package dev.gpicalter.desktop

import dev.gpicalter.core.dualHash
import dev.gpicalter.data.db.AppDatabase
import dev.gpicalter.data.entity.ImportItemEntity
import dev.gpicalter.data.entity.ImportStage
import dev.gpicalter.index.LibraryIndexer
import dev.gpicalter.media.DesktopMediaProbe
import dev.gpicalter.storage.FolderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The folder importer against real files, a real library folder and a real database. */
class FolderImporterTest {

    private lateinit var work: File
    private lateinit var source: File
    private lateinit var libraryDir: File
    private lateinit var db: AppDatabase
    private lateinit var library: FolderStore
    private lateinit var scope: CoroutineScope
    private var free: Long? = null

    @BeforeEach
    fun setUp() {
        work = Files.createTempDirectory("photohost-import").toFile()
        source = File(work, "Old Photos").apply { mkdirs() }
        libraryDir = File(work, "library").apply { mkdirs() }
        Fixtures.build(source)
        // The same photo filed twice, as archives do; a non-media file; a hidden folder.
        File(source, "copies").mkdirs()
        File(source, Fixtures.PORTRAIT).copyTo(File(source, "copies/portrait copy.jpg"))
        File(source, "notes.txt").writeText("not a photo")
        File(source, ".thumbnails").mkdirs()
        File(source, Fixtures.LANDSCAPE).copyTo(File(source, ".thumbnails/cached.jpg"))

        db = AppDatabase.open(File(work, "data/gpic.db"))
        library = FolderStore(libraryDir)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        free = null
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        db.close()
        work.deleteRecursively()
    }

    private fun importer(): FolderImporter {
        val probe = DesktopMediaProbe()
        val indexer = LibraryIndexer(db, library, volumeId = 1, probe = probe)
        return FolderImporter(db, library, indexer, probe, volumeId = 1, scope = scope, freeSpace = { free })
    }

    @Test
    fun `copies every photo once, dated, verified and with the source untouched`() = runBlocking {
        val before = snapshot(source)
        val im = importer()

        val found = im.discover(source)
        // 5 fixtures + the second copy; the text file and the hidden folder are left out.
        assertEquals(Fixtures.MEDIA_COUNT + 1, found.files)
        im.start(found.sessionId)
        val done = awaitPhase(im, ImportStatus.Phase.DONE)

        assertEquals(Fixtures.MEDIA_COUNT, done.copied)
        assertEquals(1, done.duplicates, "the second copy of the portrait is recognised by its bytes")
        assertEquals(0, done.failed)
        assertEquals(Fixtures.MEDIA_COUNT, db.assets().count())

        // Your folder is not changed: same files, same bytes, same timestamps.
        assertEquals(before, snapshot(source))

        // Laid out like phone uploads, by capture month.
        val portraitHash = File(source, Fixtures.PORTRAIT).inputStream().use { it.dualHash().contentHash }
        val portrait = File(libraryDir, "2024/03/portrait-${portraitHash.take(8)}.jpg")
        assertTrue(portrait.isFile, "expected ${portrait.path}")
        assertContentEquals(File(source, Fixtures.PORTRAIT).readBytes(), portrait.readBytes())

        val landscapeHash = File(source, Fixtures.LANDSCAPE).inputStream().use { it.dualHash().contentHash }
        assertTrue(File(libraryDir, "2024/01/IMG_20240115_123456-${landscapeHash.take(8)}.jpg").isFile)

        // No date in the file at all: the modified time decides the folder, and is kept on the copy.
        val month = DateTimeFormatter.ofPattern("yyyy/MM")
            .format(Instant.ofEpochMilli(Fixtures.SCREENSHOT_MTIME).atZone(ZoneId.systemDefault()))
        val shot = File(libraryDir, month).listFiles()!!.single { it.name.startsWith("screenshot-") }
        assertEquals(Fixtures.SCREENSHOT_MTIME, shot.lastModified())

        // Where it came from is recorded, as a phone backup records its folder.
        val asset = db.assets().byHash(portraitHash)!!
        assertEquals("2024", asset.sourceAlbum)

        assertFalse(File(libraryDir, FolderImporter.TEMP_DIR).exists(), "temporary files left behind")
    }

    @Test
    fun `importing the same folder again reads nothing and copies nothing`() = runBlocking {
        val im = importer()
        im.start(im.discover(source).sessionId)
        awaitPhase(im, ImportStatus.Phase.DONE)
        val files = libraryDir.walkTopDown().count { it.isFile }

        val again = im.discover(source)
        im.start(again.sessionId)
        val second = awaitPhase(im, ImportStatus.Phase.DONE)

        assertEquals(0, second.copied)
        assertEquals(Fixtures.MEDIA_COUNT + 1, second.unchanged, "every file recognised by path, size and date")
        assertEquals(files, libraryDir.walkTopDown().count { it.isFile })
    }

    @Test
    fun `refuses to import the library into itself or a folder containing it`() = runBlocking {
        val im = importer()
        assertFailsWith<IllegalArgumentException> { im.discover(libraryDir) }
        assertFailsWith<IllegalArgumentException> { im.discover(work) }
        assertFailsWith<IllegalArgumentException> { im.discover(File(libraryDir, "2024").apply { mkdirs() }) }
        // JUnit silently skips a test method that returns a value, and assertFailsWith returns one.
        Unit
    }

    @Test
    fun `pauses instead of filling the disk, then resumes`() = runBlocking {
        free = 100L * 1024 * 1024 // less than the 1 GiB reserve
        val im = importer()
        val found = im.discover(source)
        im.start(found.sessionId)
        val paused = awaitPhase(im, ImportStatus.Phase.PAUSED)
        assertEquals(0, paused.copied)
        assertTrue(paused.message.orEmpty().contains("free"), paused.message)

        free = null // room again
        im.start(found.sessionId)
        val done = awaitPhase(im, ImportStatus.Phase.DONE)
        assertEquals(Fixtures.MEDIA_COUNT, done.copied)
    }

    @Test
    fun `a copy interrupted mid-file is discarded and redone`() = runBlocking {
        val im = importer()
        val found = im.discover(source)

        // As a crash would leave it: one item marked COPYING with a half-written temporary file.
        val item: ImportItemEntity = db.imports().resumable(found.sessionId, 500)
            .first { it.displayName == "portrait.jpg" }
        val temp = "${FolderImporter.TEMP_DIR}/crashed.part"
        library.openWrite(temp).use { it.output.write(ByteArray(1000)) }
        db.imports().upsertItem(item.copy(stage = ImportStage.COPYING, tempRelPath = temp))

        im.start(found.sessionId)
        val done = awaitPhase(im, ImportStatus.Phase.DONE)
        assertEquals(Fixtures.MEDIA_COUNT, done.copied)
        assertEquals(0, done.failed)
        assertFalse(File(libraryDir, temp).exists())
        val redone = db.imports().item(found.sessionId, item.sourceKey)
        assertEquals(ImportStage.DONE, assertNotNull(redone).stage)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun awaitPhase(im: FolderImporter, phase: ImportStatus.Phase): ImportStatus {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = im.status.value
            if (s.phase == phase && !im.busy) return s
            if (s.phase == ImportStatus.Phase.FAILED) error("import failed: ${s.message}")
            delay(50)
        }
        error("import never reached $phase: ${im.status.value}")
    }

    /** Every file under [dir]: relative path to (hash, mtime). */
    private fun snapshot(dir: File): Map<String, Pair<String, Long>> =
        dir.walkTopDown().filter { it.isFile }.associate { f ->
            f.relativeTo(dir).path to (f.inputStream().use { it.dualHash().contentHash } to f.lastModified())
        }
}
