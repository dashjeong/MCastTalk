package app.guidecast.transmitter

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real UI + AudioRecord path. The dedicated emulator receives the public FLEURS PCM through
 * its virtual microphone using EmulatorController.injectAudio; no production input hook.
 * This is not a physical microphone/acoustic test. Run only with the host fixture injector.
 * Platform/UI-only references allow the same test to demonstrate the withdrawn APK's failure.
 */
class VoiceNoteMicrophoneJourneyDeviceTest {
    private val requiredTerms = listOf("관계자", "조언", "표지판", "안전", "경고", "주의")
    private val fixtureSha = "b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f"

    @Test fun microphoneRecordingShowsAndSavesActualSpeechBeforeStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Requires the dedicated emulator audio injector", args.getString("microphoneFixture") == "fleurs-ko")
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = requireNotNull(context.getExternalFilesDir(null))
        val fixture = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        assertEquals(fixtureSha, sha256(fixture))
        assertEquals(224_640, fixture.size)
        val notes = File(context.filesDir, "voice-notes")
        // Enumerate names only. Existing user/private notes are never opened by this test.
        val existingNames = notes.listFiles().orEmpty().map { it.name }.toSet()
        val report = JSONObject().put("fixtureSha256", fixtureSha).put("fixtureDurationMs", 7_020)
            .put("requiredKeywordCount", requiredTerms.size).put("physicalMicrophoneTest", false)
            .put("byteExactCaptureVerified", false).put("verdict", "INCOMPLETE")
        val marker = File(evidence, "synthetic-note-microphone-ready.txt")
        marker.delete()
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        val title = "마이크 경로 시험 ${System.nanoTime()}"
        var createdMetadata: File? = null
        try {
            assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 15_000))
            click(find(device, listOf("음성 노트", "녹톡")))
            requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = title
            // The withdrawn UI has only '새 녹음 시작'. Exercising it exposes the missing live text.
            click(find(device, listOf("녹음·받아쓰기 시작", "새 녹음 시작")))
            val stop = device.wait(Until.findObject(By.text("녹음 종료·저장")), 120_000)
            assertNotNull("Recording did not begin after automatic preparation", stop)
            createdMetadata = newNoteMetadata(notes, existingNames, title)
            marker.writeText("ready")
            assertTrue("Actual microphone speech must appear while recording is still active",
                device.wait(Until.hasObject(By.textContains("표지판")), 90_000))
            assertTrue(device.wait(Until.hasObject(By.textContains("경고")), 20_000))
            assertTrue("The complete fixture must be recognized before stopping the microphone",
                device.wait(Until.hasObject(By.textContains("주의")), 30_000))
            requiredTerms.forEach { term ->
                assertTrue("Missing public-fixture keyword in the live microphone UI: $term",
                    device.wait(Until.hasObject(By.textContains(term)), 10_000))
            }
            assertTrue("At least one final sentence must be saved while recording continues",
                device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("실시간 문장 · [1-9][0-9]*개 저장"))), 30_000))
            val liveNote = waitForCompleteLiveNote(requireNotNull(createdMetadata), title)
            val liveLines = originals(liveNote)
            // These are the committed strings, not a temporary partial containing a few words.
            liveLines.forEach { line ->
                assertTrue("A committed microphone sentence must be displayed before recording stops",
                    device.wait(Until.hasObject(By.text(line)), 10_000))
            }
            assertNotNull("Displayed final count must agree with the live saved note",
                device.findObject(By.text("실시간 문장 · ${liveLines.size}개 저장")))
            report.put("liveKeywordCount", requiredTerms.count { it in liveLines.joinToString(" ") })
                .put("liveFinalCount", liveLines.size).put("liveTextSha256", sha256(liveLines.joinToString("\n").toByteArray()))
            assertNotNull("Text arrived only after recording ended", device.findObject(By.text("녹음 종료·저장")))
            device.takeScreenshot(File(evidence, "synthetic-note-microphone-live.png"))
            click(find(device, listOf("녹음 종료·저장")))
            assertTrue(device.wait(Until.gone(By.text("녹음 종료·저장")), 20_000))
            assertNotNull(find(device, listOf("녹음 재생")))
            val saved = readCreatedNote(requireNotNull(createdMetadata), title)
            val savedLines = originals(saved)
            assertFalse("Successful microphone recording must be finalized", saved.getBoolean("interrupted"))
            assertEquals("Stopping must preserve the exact committed live transcript", liveLines, savedLines)
            requiredTerms.forEach { assertTrue("Saved transcript lost public-fixture keyword: $it", it in savedLines.joinToString(" ")) }
            report.put("savedKeywordCount", requiredTerms.count { it in savedLines.joinToString(" ") })
                .put("savedFinalCount", savedLines.size).put("liveSavedTextEqual", liveLines == savedLines)
                .put("savedTextSha256", sha256(savedLines.joinToString("\n").toByteArray()))
            val wav = File(notes, requireNotNull(createdMetadata).nameWithoutExtension + ".wav")
            val audio = inspectRecording(wav, fixture)
            report.put("audio", audio)
            assertTrue("Saved recording must contain at least the complete 7.02 second fixture", audio.getLong("durationMs") >= 7_020)
            assertTrue("Saved microphone audio must be non-silent", audio.getDouble("rms") > 0.002)
            // This catches the historical 91-second WAV containing only a short audio prefix.
            // It is a truncation screen, not a substitute for the host waveform correlation.
            assertTrue("Saved microphone audio contains too little signal from the public fixture",
                audio.getLong("nonzeroSamples") >= audio.getLong("fixtureNonzeroSamples") * 3 / 4)
            assertEquals("Saved metadata duration must describe the actual WAV", audio.getLong("durationMs"), saved.getLong("duration"))
            // The actual saved note opens from the library with its recognized text.
            click(find(device, listOf("보관함 (1)"), prefix = "보관함 ("))
            find(device, listOf(title))
            clickSavedNoteOpen(device, title)
            requiredTerms.forEach { assertNotNull(find(device, emptyList(), contains = it)) }
            savedLines.forEach { assertNotNull(find(device, emptyList(), contains = it)) }
            assertEquals("Reopening must not rewrite the stored transcript", savedLines,
                originals(readCreatedNote(requireNotNull(createdMetadata), title)))
            device.takeScreenshot(File(evidence, "synthetic-note-microphone-reopened.png"))
            report.put("reopenedKeywordCount", requiredTerms.size).put("verdict", "PASS")
        } catch (error: Throwable) {
            report.put("verdict", "FAIL").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            marker.delete()
            try {
                device.findObject(By.text("녹음 종료·저장"))?.let {
                    click(it)
                    device.wait(Until.gone(By.text("녹음 종료·저장")), 20_000)
                }
                device.findObject(By.text("작업 중지"))?.let(::click)
                // Evidence is limited to this newly created synthetic note. Never enumerate
                // or export other recordings; the host has disabled its physical microphone.
                createdMetadata?.let { metadata ->
                    val saved = readCreatedNote(metadata, title)
                    report.put("syntheticNoteId", saved.getString("id"))
                    val wav = File(notes, metadata.nameWithoutExtension + ".wav")
                    if (wav.isFile) {
                        report.put("audio", inspectRecording(wav, fixture))
                        wav.copyTo(File(evidence, "synthetic-note-microphone-recording.wav"), overwrite = true)
                    }
                }
            } catch (error: Throwable) {
                report.put("cleanupFailureType", error.javaClass.simpleName)
                if (report.optString("verdict") == "PASS") {
                    report.put("verdict", "FAIL")
                    throw error
                }
            } finally {
                File(evidence, "synthetic-note-microphone-result.json").writeText(report.toString(2))
            }
        }
    }

    private fun newNoteMetadata(root: File, existingNames: Set<String>, title: String): File {
        val candidates = root.listFiles().orEmpty().filter { it.extension == "json" && it.name !in existingNames }
        assertEquals("The UI must create exactly one new synthetic note", 1, candidates.size)
        return candidates.single().also { readCreatedNote(it, title) }
    }

    private fun readCreatedNote(metadata: File, title: String): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 2_000
        do {
            try {
                assertTrue("Synthetic note metadata is unexpectedly large", metadata.length() <= 4 * 1024 * 1024)
                // Read the committed base file only. Do not invoke AtomicFile recovery while
                // the application is writing its next live checkpoint on another thread.
                return JSONObject(metadata.readText()).also {
                    assertEquals("Only this test's newly created note may be inspected", title, it.getString("title"))
                    assertEquals(metadata.nameWithoutExtension, it.getString("id"))
                }
            } catch (error: java.io.FileNotFoundException) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
                SystemClock.sleep(25)
            }
        } while (true)
    }

    private fun originals(note: JSONObject): List<String> = note.getJSONArray("lines").let { lines ->
        List(lines.length()) { lines.getJSONObject(it).getString("original") }
    }

    private fun waitForCompleteLiveNote(metadata: File, title: String): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            val note = readCreatedNote(metadata, title)
            val text = originals(note).joinToString(" ")
            if (requiredTerms.all { it in text }) return note
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The live committed transcript never contained all six public-fixture keywords")
    }

    private fun inspectRecording(wav: File, fixture: ByteArray): JSONObject {
        assertTrue("Synthetic microphone WAV must exist and be bounded", wav.isFile && wav.length() in 44L..9_600_044L)
        val bytes = wav.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0)); assertEquals("WAVE", tag(8)); assertEquals("fmt ", tag(12)); assertEquals("data", tag(36))
        assertEquals("WAV must have a complete finalized RIFF length", bytes.size - 8, header.getInt(4))
        assertEquals(16, header.getInt(16)); assertEquals(1, header.getShort(20).toInt())
        assertEquals("WAV must be mono", 1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24)); assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt()); assertEquals("WAV must be signed PCM16", 16, header.getShort(34).toInt())
        val dataBytes = header.getInt(40)
        assertEquals("WAV data length must match the actual saved samples", bytes.size - 44, dataBytes)
        assertEquals(0, dataBytes % 2)
        var squares = 0.0
        var nonzero = 0L
        var peak = 0
        for (offset in 44 until bytes.size step 2) {
            val sample = header.getShort(offset).toInt()
            if (sample != 0) nonzero++
            peak = maxOf(peak, kotlin.math.abs(sample))
            squares += sample.toDouble() * sample
        }
        val fixtureSamples = ByteBuffer.wrap(fixture).order(ByteOrder.LITTLE_ENDIAN)
        val fixtureNonzero = (fixture.indices step 2).count { fixtureSamples.getShort(it).toInt() != 0 }
        return JSONObject().put("sha256", sha256(bytes)).put("bytes", bytes.size).put("dataBytes", dataBytes)
            .put("sampleRate", 16_000).put("channels", 1).put("encoding", "PCM_S16LE")
            .put("durationMs", dataBytes / 32L).put("nonzeroSamples", nonzero).put("fixtureNonzeroSamples", fixtureNonzero)
            .put("peak", peak).put("rms", if (dataBytes == 0) 0.0 else sqrt(squares / (dataBytes / 2)) / 32768.0)
            .put("waveformIdentityVerified", false)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun clickSavedNoteOpen(device: UiDevice, title: String) {
        var previous: Rect? = null
        var stable = 0
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            refreshTestAccessibilityCache()
            val bounds = try {
                var row = device.findObject(By.text(title))
                while (row != null && row.findObject(By.text("열기")) == null) row = row.parent
                row?.findObject(By.text("열기"))?.takeIf { it.isEnabled }?.visibleBounds
            } catch (_: StaleObjectException) { null }
            stable = if (bounds != null && !bounds.isEmpty && bounds == previous) stable + 1 else 0
            previous = bounds
            if (stable >= 6) {
                val ready = requireNotNull(bounds)
                assertTrue(device.click(ready.centerX(), ready.centerY()))
                device.waitForIdle()
                refreshTestAccessibilityCache()
                return
            }
            SystemClock.sleep(75L)
        }
        error("Saved note open control did not settle: $title")
    }

    private fun click(label: UiObject2) {
        var target = label
        while (!target.isClickable && target.parent != null) target = requireNotNull(target.parent)
        check(target.isClickable && target.isEnabled) { "Control is not actionable" }
        target.click()
    }

    private fun find(device: UiDevice, labels: List<String>, prefix: String? = null, contains: String? = null): UiObject2 {
        for (direction in listOf(Direction.UP, Direction.DOWN)) repeat(12) {
            device.waitForIdle()
            refreshTestAccessibilityCache()
            labels.firstNotNullOfOrNull { device.findObject(By.text(it)) }?.let { return it }
            if (prefix != null) device.findObject(By.textStartsWith(prefix))?.let { return it }
            if (contains != null) device.findObject(By.textContains(contains))?.let { return it }
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                ?.scroll(direction, .65f)
        }
        error("User control unreachable: ${labels.joinToString()}")
    }
}
