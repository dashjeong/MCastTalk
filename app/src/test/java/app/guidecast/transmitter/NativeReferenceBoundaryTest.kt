package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeReferenceBoundaryTest {
    private fun candidate(): NativeReferenceSnapshot {
        val (payload, count) = nativeReferencePayload(listOf(NativeReferenceEntry("Terms", "TERMS", "수율 = yield")))
        return NativeReferenceSnapshot(payload, count, 1)
    }

    @Test fun attachedCredentialShapeCannotEnterReferenceOrDirectInstructions() {
        for (value in listOf("prefix" + "sk-" + "a".repeat(30), "prefix" + "AIza" + "a".repeat(35))) {
            assertTrue(containsNativeContextCredentialLikeText(value))
            assertFalse(validInterpreterInstructions(value))
            for (field in listOf("title", "kind", "text")) {
                val entry = NativeReferenceEntry(if (field == "title") value else "Terms", if (field == "kind") value else "TERMS",
                    if (field == "text") value else "수율 = yield")
                assertEquals("" to 0, nativeReferencePayload(listOf(entry)))
                val raw = JSONObject().put("references", JSONArray().put(JSONObject().put("title", entry.title)
                    .put("kind", entry.kind).put("text", entry.text))).toString()
                assertThrows(IllegalArgumentException::class.java) {
                    nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, "", "", raw)
                }
            }
        }
    }

    @Test fun mappingThatCannotFitIsExcludedRatherThanCutBeforeItsTranslation() {
        val empty = JSONObject().put("references", JSONArray().put(JSONObject().put("title", "Terms")
            .put("kind", "TERMS").put("text", ""))).toString().length
        val budget = 600 - empty
        val body = "a".repeat(budget - 1) + "=yield"
        assertEquals("" to 0, nativeReferencePayload(listOf(NativeReferenceEntry("Terms", "TERMS", body))))
    }

    @Test fun oversizeMappingCannotHideFollowingCompleteTerm() {
        val body = "a".repeat(650) + "=not ready\n수율 = yield"
        val (payload, count) = nativeReferencePayload(listOf(NativeReferenceEntry("Terms", "TERMS", body)))
        assertEquals(1, count)
        assertEquals("수율 = yield", nativeReferencePreview(payload).single().text)
    }

    @Test fun deniedReferencesHaveNoSelectedTransmissionPayload() {
        val selected = nativeReferenceTransmissionPreview(candidate(), supported = true, permission = false)
        assertEquals("", selected.payload); assertEquals(0, selected.includedEntries)
    }

    @Test fun unsupportedModelHasNoSelectedTransmissionPayload() {
        val selected = nativeReferenceTransmissionPreview(candidate(), supported = false, permission = true)
        assertEquals("", selected.payload); assertEquals(0, selected.includedEntries)
    }

    @Test fun unsupportedModelExplainsCapabilityInsteadOfClaimingPermissionMissing() {
        assertTrue(nativeReferencePreviewNotice(candidate(), supported = false, permission = true).contains("지원하지"))
    }

    @Test fun refreshFailureInvalidatesThePreviousPreparedExcerpt() = runTest {
        val refreshed = refreshNativeReferencePreview { throw IllegalStateException("Synthetic failure") }
        assertTrue(refreshed.failed)
        assertEquals("", refreshed.references.payload)
        assertEquals(0, refreshed.references.includedEntries)
    }

    @Test fun completeMappingsKeepNegationNumbersAndEscapedTextWithVisibleOmissions() {
        val mapping = "허용하지 않음 = do not allow 20 mg\n경로 = C:\\tools\\\"quoted\"\n😀 = smile"
        val prepared = prepareNativeReferencePayload(listOf(NativeReferenceEntry("Terms", "TERMS",
            "a".repeat(650) + "=oversize\n\n" + mapping)))
        assertEquals(1, prepared.includedEntries)
        assertEquals(1, prepared.omittedTermLines)
        assertEquals(mapping, nativeReferencePreview(prepared.payload).single().text)
        assertTrue(prepared.payload.length <= MAX_NATIVE_REFERENCE_CHARS)
    }

    @Test fun credentialAfterExcerptBudgetRejectsTheWholeStoredCandidate() {
        val text = "safe ".repeat(130) + "prefix" + "sk-" + "b".repeat(30)
        assertEquals("" to 0, nativeReferencePayload(listOf(NativeReferenceEntry("Lecture", "LECTURE", text))))
    }

    @Test fun supportedPermissionSelectsExactlyThePreparedCandidate() {
        val prepared = candidate()
        assertEquals(prepared, nativeReferenceTransmissionPreview(prepared, true, true))
        assertTrue(nativeReferencePreviewNotice(NativeReferenceSnapshot(), true, true).contains("발췌가 없어"))
    }

    @Test fun cancelledRefreshCannotPublishALateNonCancellableResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        var published: NativeReferencePreviewLoad? = null
        val job = launch {
            published = refreshNativeReferencePreview {
                withContext(NonCancellable) { gate.await(); candidate() }
            }
        }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertNull(published)
    }

    @Test fun cancelledFailedRefreshCannotInvalidateANewerPreparedResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val newer = NativeReferencePreviewLoad(candidate())
        var displayed = newer
        val job = launch {
            displayed = refreshNativeReferencePreview {
                withContext(NonCancellable) { gate.await(); throw IllegalStateException("Synthetic late failure") }
            }
        }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(newer, displayed)
    }
}
