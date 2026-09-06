package app.guidecast.provider.moonshine.tts

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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

class MoonshineTtsModelIntegrityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactSizesProduceAtomicReadyMarkerThatCanBeRecheckedWithoutManifestQuery() {
        val directory = temporaryFolder.newFolder("voice")
        File(directory, "kokoro/model.onnx").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        File(directory, "voices.bin").writeBytes(byteArrayOf(5, 6))
        val expected = listOf(
            MoonshineTtsExpectedFile("kokoro/model.onnx", 4),
            MoonshineTtsExpectedFile("voices.bin", 2),
        )

        MoonshineTtsModelIntegrity.writeReadyMarker(directory, expected)

        assertTrue(MoonshineTtsModelIntegrity.isReady(directory, expected))
        assertTrue(MoonshineTtsModelIntegrity.isReady(directory))
        assertTrue(File(directory, ".guidecast-integrity-v1").isFile)
        assertFalse(
            directory.listFiles().orEmpty().any { file -> file.name.endsWith(".tmp") },
        )
    }

    @Test
    fun byteLengthMismatchInvalidatesAnExistingMarker() {
        val directory = temporaryFolder.newFolder("voice")
        val model = File(directory, "model.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val expected = listOf(MoonshineTtsExpectedFile("model.onnx", 3))
        MoonshineTtsModelIntegrity.writeReadyMarker(directory, expected)

        model.appendBytes(byteArrayOf(4))

        assertFalse(MoonshineTtsModelIntegrity.isReady(directory))
        assertFalse(MoonshineTtsModelIntegrity.filesMatch(directory, expected))
    }

    @Test
    fun officialSha256IsEnforcedWhenManifestSuppliesIt() {
        val directory = temporaryFolder.newFolder("voice")
        val bytes = "clear speech".toByteArray()
        val model = File(directory, "voice.bin").apply { writeBytes(bytes) }
        val expected = listOf(
            MoonshineTtsExpectedFile(
                relativePath = "voice.bin",
                expectedSize = bytes.size.toLong(),
                sha256 = sha256(bytes),
            ),
        )
        MoonshineTtsModelIntegrity.writeReadyMarker(directory, expected)
        assertTrue(MoonshineTtsModelIntegrity.isReady(directory, expected))
        assertTrue(MoonshineTtsModelIntegrity.hasReadySnapshot(directory, expected))

        model.writeBytes("other speech".toByteArray())

        // Startup checks marker/path/size only; the isolated worker catches same-size corruption
        // with a full pinned hash before native loading.
        assertTrue(MoonshineTtsModelIntegrity.hasReadySnapshot(directory, expected))
        assertFalse(MoonshineTtsModelIntegrity.isReady(directory))
    }

    @Test
    fun failedFullVerificationCannotWriteAReadyMarker() {
        val directory = temporaryFolder.newFolder("invalid-voice")
        File(directory, "voice.bin").writeBytes(byteArrayOf(1, 2, 3))
        val expected = listOf(
            MoonshineTtsExpectedFile(
                relativePath = "voice.bin",
                expectedSize = 3,
                sha256 = sha256(byteArrayOf(3, 2, 1)),
            ),
        )

        assertFalse(
            MoonshineTtsModelIntegrity.writeReadyMarkerIfFilesMatch(directory, expected),
        )
        assertFalse(File(directory, ".guidecast-integrity-v1").exists())
    }

    @Test
    fun changedOfficialManifestCannotReuseOlderMarker() {
        val directory = temporaryFolder.newFolder("voice")
        File(directory, "model.onnx").writeBytes(byteArrayOf(1, 2, 3))
        val installed = listOf(MoonshineTtsExpectedFile("model.onnx", 3))
        MoonshineTtsModelIntegrity.writeReadyMarker(directory, installed)
        val changedManifest = listOf(
            MoonshineTtsExpectedFile("model.onnx", 3),
            MoonshineTtsExpectedFile("new-config.json", 0),
        )

        assertFalse(MoonshineTtsModelIntegrity.isReady(directory, changedManifest))
    }

    @Test
    fun cleanupRemovesOnlyInvalidArtifactsInsideOneVoice() {
        val modelRoot = temporaryFolder.newFolder("models")
        val targetVoice = File(modelRoot, "tts-en-us").apply { mkdirs() }
        val siblingVoice = File(modelRoot, "tts-ja-jp").apply { mkdirs() }
        val valid = File(targetVoice, "valid.bin").apply { writeBytes(ByteArray(4) { 1 }) }
        val invalid = File(targetVoice, "invalid.bin").apply { writeBytes(ByteArray(2) { 2 }) }
        val partial = File(targetVoice, "missing.bin.part").apply { writeBytes(byteArrayOf(3)) }
        val sibling = File(siblingVoice, "model.onnx").apply { writeText("preserve") }
        val expected = listOf(
            MoonshineTtsExpectedFile("valid.bin", 4),
            MoonshineTtsExpectedFile("invalid.bin", 8),
            MoonshineTtsExpectedFile("missing.bin", 16),
        )

        val removed = MoonshineTtsModelIntegrity.removeInvalidArtifacts(
            modelRoot,
            targetVoice,
            expected,
        )

        assertEquals(1, removed)
        assertTrue(valid.isFile)
        assertFalse(invalid.exists())
        assertTrue(partial.isFile)
        assertTrue(sibling.isFile)
    }

    @Test
    fun interruptedDownloadKeepsOnlySafeIncompletePartForHttpRangeResume() {
        val modelRoot = temporaryFolder.newFolder("resume-models")
        val targetVoice = File(modelRoot, "tts-en-us").apply { mkdirs() }
        val resumable = File(targetVoice, "resumable.bin.part").apply {
            writeBytes(ByteArray(7) { 1 })
        }
        val completeButUnfinalized = File(targetVoice, "complete.bin.part").apply {
            writeBytes(ByteArray(8) { 2 })
        }
        val oversized = File(targetVoice, "oversized.bin.part").apply {
            writeBytes(ByteArray(9) { 3 })
        }
        val empty = File(targetVoice, "empty.bin.part").apply { createNewFile() }
        val expected = listOf(
            MoonshineTtsExpectedFile("resumable.bin", 8),
            MoonshineTtsExpectedFile("complete.bin", 8),
            MoonshineTtsExpectedFile("oversized.bin", 8),
            MoonshineTtsExpectedFile("empty.bin", 8),
        )

        val removed = MoonshineTtsModelIntegrity.removeInvalidArtifacts(
            modelRoot,
            targetVoice,
            expected,
        )

        assertEquals(3, removed)
        assertTrue(resumable.isFile)
        assertFalse(completeButUnfinalized.exists())
        assertFalse(oversized.exists())
        assertFalse(empty.exists())
    }

    @Test
    fun invalidFinalArtifactAlsoInvalidatesOtherwiseResumablePart() {
        val modelRoot = temporaryFolder.newFolder("invalid-final-models")
        val targetVoice = File(modelRoot, "tts-en-us").apply { mkdirs() }
        val invalidFinal = File(targetVoice, "model.bin").apply {
            writeBytes(ByteArray(3) { 1 })
        }
        val partial = File(targetVoice, "model.bin.part").apply {
            writeBytes(ByteArray(4) { 2 })
        }

        val removed = MoonshineTtsModelIntegrity.removeInvalidArtifacts(
            modelRoot,
            targetVoice,
            listOf(MoonshineTtsExpectedFile("model.bin", 8)),
        )

        assertEquals(2, removed)
        assertFalse(invalidFinal.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun partialSymlinkIsRemovedWithoutTouchingItsTarget() {
        val modelRoot = temporaryFolder.newFolder("symlink-models")
        val targetVoice = File(modelRoot, "tts-en-us").apply { mkdirs() }
        val outside = File(modelRoot, "outside.bin").apply { writeBytes(ByteArray(4) { 7 }) }
        val partial = File(targetVoice, "model.bin.part")
        Files.createSymbolicLink(partial.toPath(), outside.toPath())

        val removed = MoonshineTtsModelIntegrity.removeInvalidArtifacts(
            modelRoot,
            targetVoice,
            listOf(MoonshineTtsExpectedFile("model.bin", 8)),
        )

        assertEquals(1, removed)
        assertFalse(Files.exists(partial.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(outside.isFile)
        assertEquals(4L, outside.length())
    }

    @Test
    fun cleanupRejectsDirectoryOutsideMoonshineModelRoot() {
        val modelRoot = temporaryFolder.newFolder("models")
        val outside = temporaryFolder.newFolder("outside")
        val protectedFile = File(outside, "model.onnx").apply { writeText("preserve") }

        assertThrows(IllegalArgumentException::class.java) {
            MoonshineTtsModelIntegrity.removeInvalidArtifacts(
                modelRoot,
                outside,
                listOf(MoonshineTtsExpectedFile("model.onnx", 100)),
            )
        }
        assertTrue(protectedFile.isFile)
    }

    @Test
    fun unsafeOrDuplicateManifestPathsCannotBeMarkedReady() {
        val directory = temporaryFolder.newFolder("voice")

        assertThrows(IllegalArgumentException::class.java) {
            MoonshineTtsModelIntegrity.writeReadyMarker(
                directory,
                listOf(MoonshineTtsExpectedFile("../escape.onnx", 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoonshineTtsModelIntegrity.writeReadyMarker(
                directory,
                listOf(
                    MoonshineTtsExpectedFile("same.bin", 1),
                    MoonshineTtsExpectedFile("same.bin", 1),
                ),
            )
        }
    }

    @Test
    fun concurrentMarkerReplacementAlwaysLeavesACompleteMarker() {
        val directory = temporaryFolder.newFolder("voice")
        File(directory, "model.onnx").writeBytes(ByteArray(32) { it.toByte() })
        val expected = listOf(MoonshineTtsExpectedFile("model.onnx", 32))
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val futures = List(12) {
                executor.submit {
                    start.await()
                    MoonshineTtsModelIntegrity.writeReadyMarker(directory, expected)
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            assertTrue(MoonshineTtsModelIntegrity.isReady(directory, expected))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
