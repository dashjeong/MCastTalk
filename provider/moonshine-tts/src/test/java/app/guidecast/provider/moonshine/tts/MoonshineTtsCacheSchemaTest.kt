package app.guidecast.provider.moonshine.tts

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MoonshineTtsCacheSchemaTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyMigrationPreservesDownloadsForPerVoiceManifestValidation() {
        val fixture = fixture()
        fixture.voiceDirectories.forEachIndexed { index, directory ->
            directory.mkdirs()
            File(directory, "legacy-$index.onnx").writeText("old")
        }
        val sttCache = File(fixture.modelRoot, "stt-ko-0").apply { mkdirs() }
        val sttModel = File(sttCache, "model.onnx").apply { writeText("keep") }
        val unrelatedAppData = File(fixture.base, "gemma/model.litertlm").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }

        val result = fixture.migrate()

        assertTrue(result.ready)
        assertEquals(0, result.clearedDirectoryCount)
        fixture.voiceDirectories.forEachIndexed { index, directory ->
            assertTrue(File(directory, "legacy-$index.onnx").isFile)
        }
        assertTrue(sttModel.isFile)
        assertTrue(unrelatedAppData.isFile)
        assertEquals("3", fixture.marker.readText().trim())
    }

    @Test
    fun freshInstallWritesMarkerWithoutDeletingOrCreatingVoiceCaches() {
        val fixture = fixture()
        // Empty directories can exist after a harmless ModelCache lookup. They contain no legacy
        // download and therefore do not need to be deleted on a fresh installation.
        fixture.voiceDirectories.take(2).forEach(File::mkdirs)
        val unrelated = File(fixture.modelRoot, "intent-tour-guide").apply {
            mkdirs()
            File(this, "model.bin").writeText("keep")
        }

        val result = fixture.migrate()

        assertTrue(result.ready)
        assertEquals(0, result.clearedDirectoryCount)
        assertTrue(fixture.voiceDirectories[0].isDirectory)
        assertTrue(fixture.voiceDirectories[1].isDirectory)
        assertFalse(fixture.voiceDirectories[2].exists())
        assertFalse(fixture.voiceDirectories[3].exists())
        assertFalse(fixture.voiceDirectories[4].exists())
        assertFalse(fixture.voiceDirectories[5].exists())
        assertTrue(File(unrelated, "model.bin").isFile)
        assertEquals("3", fixture.marker.readText().trim())
    }

    @Test
    fun currentMarkerPreservesModelsDownloadedAfterMigration() {
        val fixture = fixture()
        assertTrue(fixture.migrate().ready)
        val currentModel = File(fixture.voiceDirectories.first(), "current.onnx").apply {
            parentFile?.mkdirs()
            writeText("new")
        }

        val second = fixture.migrate()

        assertTrue(second.ready)
        assertEquals(0, second.clearedDirectoryCount)
        assertTrue(currentModel.isFile)
    }

    @Test
    fun concurrentCreationWritesSchemaMarkerWithoutDeletingDownloads() {
        val fixture = fixture()
        fixture.voiceDirectories.forEach { directory ->
            directory.mkdirs()
            File(directory, "legacy.onnx").writeText("old")
        }
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = List(8) {
                executor.submit<MoonshineTtsCacheSchemaState> {
                    start.await()
                    fixture.migrate()
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertTrue(results.all { it.ready })
            assertEquals(0, results.sumOf { it.clearedDirectoryCount })
            fixture.voiceDirectories.forEach { directory ->
                assertTrue(File(directory, "legacy.onnx").isFile)
            }
            assertEquals("3", fixture.marker.readText().trim())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun versionOneUpgradePreservesPreviouslyDownloadedVoice() {
        val fixture = fixture()
        fixture.marker.parentFile?.mkdirs()
        fixture.marker.writeText("1\n")
        val cachedVoice = File(fixture.voiceDirectories.first(), "model.onnx").apply {
            parentFile?.mkdirs()
            writeText("validity will be checked by the worker")
        }

        val result = fixture.migrate()

        assertTrue(result.ready)
        assertEquals(0, result.clearedDirectoryCount)
        assertTrue(cachedVoice.isFile)
        assertEquals("3", fixture.marker.readText().trim())
    }

    @Test
    fun versionTwoCatalogExpansionPreservesAllPreviouslyDownloadedVoices() {
        val fixture = fixture()
        fixture.marker.parentFile?.mkdirs()
        fixture.marker.writeText("2\n")
        val cachedFiles = fixture.voiceDirectories.take(4).mapIndexed { index, directory ->
            File(directory, "voice-$index.ort").apply {
                parentFile?.mkdirs()
                writeText("preserve")
            }
        }

        val result = fixture.migrate()

        assertTrue(result.ready)
        assertEquals(0, result.clearedDirectoryCount)
        assertTrue(cachedFiles.all(File::isFile))
        assertFalse(fixture.voiceDirectories[4].exists())
        assertFalse(fixture.voiceDirectories[5].exists())
        assertEquals("3", fixture.marker.readText().trim())
    }

    @Test
    fun invalidScopeFailsBeforeDeletionAndDoesNotWriteMarker() {
        val fixture = fixture()
        val protectedLegacyFile = File(fixture.voiceDirectories.first(), "legacy.onnx").apply {
            parentFile?.mkdirs()
            writeText("old")
        }
        val outside = File(fixture.base, "outside/tts-en-us")
        val invalidTargets = fixture.voiceDirectories.dropLast(1) + outside

        assertThrows(IllegalArgumentException::class.java) {
            MoonshineTtsCacheSchema.migrate(
                modelRoot = fixture.modelRoot,
                officialVoiceDirectories = invalidTargets,
                markerFile = fixture.marker,
                lockFile = fixture.lock,
            )
        }

        assertTrue(protectedLegacyFile.isFile)
        assertFalse(fixture.marker.exists())
    }

    private fun fixture(): Fixture {
        val base = temporaryFolder.newFolder()
        val modelRoot = File(base, "moonshine-models")
        val voiceDirectories = listOf(
            "tts-en-us",
            "tts-ja-jp",
            "tts-zh-hans",
            "tts-nl-nl",
            "tts-es-mx",
            "tts-ar-msa",
        ).map { File(modelRoot, it) }
        val stateRoot = File(base, "guidecast-cache-schema")
        return Fixture(
            base = base,
            modelRoot = modelRoot,
            voiceDirectories = voiceDirectories,
            marker = File(stateRoot, "moonshine-tts.version"),
            lock = File(stateRoot, "moonshine-tts.lock"),
        )
    }

    private data class Fixture(
        val base: File,
        val modelRoot: File,
        val voiceDirectories: List<File>,
        val marker: File,
        val lock: File,
    ) {
        fun migrate(): MoonshineTtsCacheSchemaState = MoonshineTtsCacheSchema.migrate(
            modelRoot = modelRoot,
            officialVoiceDirectories = voiceDirectories,
            markerFile = marker,
            lockFile = lock,
        )
    }
}
