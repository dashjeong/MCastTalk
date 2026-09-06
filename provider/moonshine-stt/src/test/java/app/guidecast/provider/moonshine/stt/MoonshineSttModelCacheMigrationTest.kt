package app.guidecast.provider.moonshine.stt

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineSttModelCacheMigrationTest {
    @Test
    fun migratesOnlyExactKoreanTinyDirectoryAndRepairsSameSizeCorruption() {
        withTemporaryCache { root ->
            val target = File(root, TARGET_KEY).apply { mkdirs() }
            val sibling = File(root, "tts-en").apply { mkdirs() }
            val siblingBytes = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
            File(sibling, "voice.onnx").writeBytes(siblingBytes)
            File(target, MODEL_FILE).writeBytes(byteArrayOf(0, 0))
            val downloads = AtomicInteger(0)
            val migration = migration(root, target)

            assertTrue(
                migration.prepare(EXPECTED_FILES) {
                    downloads.incrementAndGet()
                    File(target, MODEL_FILE).writeBytes(FRESH_MODEL)
                },
            )
            assertArrayEquals(siblingBytes, File(sibling, "voice.onnx").readBytes())
            assertEquals(1, downloads.get())

            assertFalse(
                migration.prepare(EXPECTED_FILES) {
                    error("A valid schema marker must not download twice")
                },
            )

            // Existence and length are unchanged, but SHA-256 differs. This must be repaired
            // instead of reaching MicTranscriber.load().
            File(target, MODEL_FILE).writeBytes(byteArrayOf(9, 9, 9, 9))
            assertTrue(
                migration.prepare(EXPECTED_FILES) {
                    downloads.incrementAndGet()
                    File(target, MODEL_FILE).writeBytes(FRESH_MODEL)
                },
            )
            assertEquals(2, downloads.get())
            assertArrayEquals(FRESH_MODEL, File(target, MODEL_FILE).readBytes())
            assertArrayEquals(siblingBytes, File(sibling, "voice.onnx").readBytes())
        }
    }

    @Test
    fun preservesExactLegacyModelAndCreatesMarkerWithoutDownloading() {
        withTemporaryCache { root ->
            val target = File(root, TARGET_KEY).apply { mkdirs() }
            File(target, MODEL_FILE).writeBytes(FRESH_MODEL)
            val migration = migration(root, target)

            assertTrue(
                migration.prepare(EXPECTED_FILES) {
                    error("An exact official legacy model must be preserved")
                },
            )
            assertArrayEquals(FRESH_MODEL, File(target, MODEL_FILE).readBytes())

            assertFalse(
                migration.prepare(EXPECTED_FILES) {
                    error("A valid marker must avoid a second download")
                },
            )
        }
    }

    @Test
    fun redownloadsUnmarkedLegacyFileWhenSizeMatchesButDigestDoesNot() {
        withTemporaryCache { root ->
            val target = File(root, TARGET_KEY).apply { mkdirs() }
            File(target, MODEL_FILE).writeBytes(byteArrayOf(9, 9, 9, 9))
            val downloads = AtomicInteger(0)

            assertTrue(
                migration(root, target).prepare(EXPECTED_FILES) {
                    downloads.incrementAndGet()
                    File(target, MODEL_FILE).writeBytes(FRESH_MODEL)
                },
            )

            assertEquals(1, downloads.get())
            assertArrayEquals(FRESH_MODEL, File(target, MODEL_FILE).readBytes())
        }
    }

    @Test
    fun promotesPriorSchemaOnlyAfterPinnedBytesAreVerified() {
        withTemporaryCache { root ->
            val target = File(root, TARGET_KEY).apply { mkdirs() }
            File(target, MODEL_FILE).writeBytes(FRESH_MODEL)
            File(target, ".guidecast-cache-schema").writeText(
                "schema=guidecast-stt-test-v1\nkey=$TARGET_KEY\n",
            )

            assertTrue(
                migration(root, target).prepare(EXPECTED_FILES) {
                    error("Pinned prior-schema bytes must not be downloaded again")
                },
            )
            assertTrue(
                File(target, ".guidecast-cache-schema")
                    .readText()
                    .startsWith("schema=$SCHEMA_VERSION\n"),
            )
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsDownloadedFileWhenDigestDoesNotMatchPinnedArtifact() {
        withTemporaryCache { root ->
            val target = File(root, TARGET_KEY).apply { mkdirs() }

            migration(root, target).prepare(EXPECTED_FILES) {
                File(target, MODEL_FILE).writeBytes(byteArrayOf(9, 9, 9, 9))
            }
        }
    }

    @Test
    fun concurrentPreparationsDownloadTheScopedModelOnlyOnce() {
        withTemporaryCache { root ->
            val target = File(root, TARGET_KEY).apply { mkdirs() }
            val downloads = AtomicInteger(0)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = List(2) {
                    pool.submit<Boolean> {
                        start.await(5, TimeUnit.SECONDS)
                        migration(root, target).prepare(EXPECTED_FILES) {
                            downloads.incrementAndGet()
                            Thread.sleep(100)
                            File(target, MODEL_FILE).writeBytes(FRESH_MODEL)
                        }
                    }
                }
                start.countDown()
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(1, downloads.get())
                assertEquals(1, results.count { it })
                assertEquals(1, results.count { !it })
                assertArrayEquals(FRESH_MODEL, File(target, MODEL_FILE).readBytes())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun migration(root: File, target: File) = ScopedModelCacheMigration(
        rootDirectory = root,
        targetDirectory = target,
        targetKey = TARGET_KEY,
        schemaVersion = SCHEMA_VERSION,
    )

    private inline fun withTemporaryCache(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("guidecast-stt-cache-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val TARGET_KEY = "stt-ko-0"
        private const val MODEL_FILE = "model.bin"
        private const val SCHEMA_VERSION = "guidecast-stt-test-v2"
        private val FRESH_MODEL = byteArrayOf(1, 2, 3, 4)
        private val EXPECTED_FILES = listOf(
            ExpectedModelFile(
                relativePath = MODEL_FILE,
                expectedSize = FRESH_MODEL.size.toLong(),
                expectedSha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            ),
        )
    }
}
