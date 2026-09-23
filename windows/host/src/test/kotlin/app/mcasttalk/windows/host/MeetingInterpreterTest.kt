package app.mcasttalk.windows.host

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class MeetingInterpreterTest {
    private class Fixture : AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val directory=RoomDirectory();val hub=RoomSocketHub();val membership=RoomMembershipCoordinator(directory,hub)
        val started=CountDownLatch(1);val release=CountDownLatch(1);val requests=CopyOnWriteArrayList<Map<String,Any?>>()
        var fail=false;var noSpeech=false;var stream=false
        val interpreter=MeetingInterpreter(object:MeetingInference {
            override fun executeStreaming(request:Map<String,Any?>,onEvent:(FlatJsonObject)->Unit):FlatJsonObject {
                if(!stream)return execute(request)
                val targets=request["targets"].toString().split(',')
                val audio=request["audioTargets"].toString().split(',')
                targets.forEach { target ->
                    onEvent(parseFlatJsonObject(encodeJson(mapOf("status" to "partial","kind" to "caption","targetLanguage" to target,
                        "elapsedMs" to 10,"asrMs" to 1,"originalText" to "synthetic","publishedText" to "synthetic","translatedText" to "translated $target"))))
                    if(target in audio)onEvent(parseFlatJsonObject(encodeJson(mapOf("status" to "partial","kind" to "audio","targetLanguage" to target,
                        "elapsedMs" to 20,"audioWav" to "fixture"))))
                }
                return execute(request)
            }
            override fun execute(request:Map<String,Any?>):FlatJsonObject {
                requests.add(request);started.countDown();check(release.await(5,TimeUnit.SECONDS))
                if(fail)error("private synthetic error must not be exposed")
                val result=linkedMapOf<String,Any?>("status" to if(noSpeech) "no_speech" else "complete","originalText" to (request["text"]?:"synthetic speech"),"publishedText" to "published","elapsedMs" to 1)
                request["targets"].toString().split(',').forEach { result["text_$it"]="translated $it";result["audio_$it"]="fixture" }
                return parseFlatJsonObject(encodeJson(result))
            }
            override fun close(){release.countDown()}
        },membership,scope)
        data class Peer(val person:Participant,val connection:RoomSocketHub.Connection,val messages:CopyOnWriteArrayList<String>)
        fun join(id:String,language:String="ko",role:ParticipantRole=ParticipantRole.SPEAKER,secondary:String?=null):Peer {
            val person=Participant(id,id,ParticipantPreferences.create(language,listenLanguage=language,secondaryOriginalLanguage=secondary),role=role)
            val messages=CopyOnWriteArrayList<String>()
            val connection=membership.join("room-test",person){hub.attach("room-test",id,scope,{messages.add(it)},{},presenceId=person.presenceId)}
            return Peer(person,connection,messages)
        }
        fun submit(peer:Peer,recipient:Peer?=null,voice:Boolean=false){
            interpreter.submit("room-test",peer.person,peer.connection,
                text=if(voice)null else ChatSendCommand("synthetic",recipient?.person?.id,recipient?.person?.presenceId),
                voice=if(voice)VoiceUtterance(1,ByteArray(8000))else null)
        }
        fun result(peer:Peer,type:String):String {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(System.nanoTime()<end){peer.messages.firstOrNull{it.contains("\"type\":\"$type\"")}?.let{return it};Thread.sleep(5)}
            error("No $type event")
        }
        fun complete(){assertTrue(started.await(5,TimeUnit.SECONDS));release.countDown()}
        override fun close(){release.countDown();interpreter.close();scope.cancel()}
    }
    @Test fun deduplicatesLanguagesAndDoesNotSynthesizeForText()=Fixture().use { f ->
        val a=f.join("alice","en");val b=f.join("bob");f.join("carol");f.submit(a);f.complete();f.result(b,"CHAT_MESSAGE")
        assertEquals(setOf("en","ko"),f.requests.single()["targets"].toString().split(',').toSet())
        assertEquals("",f.requests.single()["audioTargets"])
    }
    @Test fun privateTranslationIsNeverDeliveredToThirdParticipant()=Fixture().use { f ->
        val a=f.join("alice","en");val b=f.join("bob");val c=f.join("carol","ja")
        f.submit(a,b);f.complete();val event=parseFlatJsonObject(f.result(b,"CHAT_MESSAGE"));f.result(a,"CHAT_MESSAGE")
        assertEquals("private",event.requiredString("scope"));assertEquals(b.person.presenceId,event.requiredString("recipientPresenceId"))
        assertFalse(c.messages.any{it.contains("\"type\":\"CHAT_MESSAGE\"")})
        assertFalse(f.requests.single()["targets"].toString().contains("ja"))
    }
    @Test fun voiceRecipientGetsAudioButSpeakerDoesNot()=Fixture().use { f ->
        val a=f.join("alice","en");val b=f.join("bob");f.submit(a,voice=true);f.complete()
        assertEquals("fixture",parseFlatJsonObject(f.result(b,"INTERPRETATION")).requiredString("audioWav"))
        assertFalse(parseFlatJsonObject(f.result(a,"INTERPRETATION")).contains("audioWav"))
        assertEquals("ko",f.requests.single()["audioTargets"])
    }
    @Test fun replacementPresenceDoesNotReceiveOldPrivateTranslation()=Fixture().use { f ->
        val a=f.join("alice","en");val b=f.join("bob");f.submit(a,b);assertTrue(f.started.await(5,TimeUnit.SECONDS))
        f.membership.leave("room-test",b.person,b.connection);val replacement=f.join("bob")
        f.complete();f.result(a,"CHAT_MESSAGE");assertFalse(replacement.messages.any{it.contains("\"type\":\"CHAT_MESSAGE\"")})
    }
    @Test fun newParticipantDoesNotReceiveHistoricalVoice()=Fixture().use { f ->
        val a=f.join("alice");f.submit(a,voice=true);assertTrue(f.started.await(5,TimeUnit.SECONDS));val b=f.join("bob")
        f.complete();f.result(a,"INTERPRETATION");assertFalse(b.messages.any{it.contains("\"type\":\"INTERPRETATION\"")})
    }
    @Test fun oneOutstandingWorkPerPresence()=Fixture().use { f ->
        val a=f.join("alice");f.submit(a);assertTrue(f.started.await(5,TimeUnit.SECONDS))
        assertThrows(IllegalArgumentException::class.java){f.submit(a)}
        assertEquals(1,f.requests.size)
    }
    @Test fun boundedQueueRejectsExcessWork():Unit=Fixture().use { f ->
        val peers=(0..5).map { f.join("peer-$it") };f.submit(peers.first());assertTrue(f.started.await(5,TimeUnit.SECONDS))
        peers.subList(1,5).forEach(f::submit)
        assertThrows(IllegalArgumentException::class.java){f.submit(peers.last())}
    }
    @Test fun listenerCannotSubmitInference()=Fixture().use { f ->
        val listener=f.join("listener",role=ParticipantRole.LISTENER)
        assertThrows(IllegalArgumentException::class.java){f.submit(listener,voice=true)}
        assertTrue(f.requests.isEmpty())
    }
    @Test fun workerFailureDoesNotExposePrivateDiagnostic()=Fixture().use { f ->
        val a=f.join("alice");f.fail=true;f.submit(a);f.complete();val result=f.result(a,"ERROR")
        assertTrue(result.contains("TRANSLATION_FAILED"));assertFalse(result.contains("private synthetic error"))
        assertEquals("failed",parseFlatJsonObject(f.result(a,"CHAT_MESSAGE")).requiredString("translationStatus"))
    }
    @Test fun noSpeechIsExplicitWithoutInventedCaption()=Fixture().use { f ->
        val a=f.join("alice");f.noSpeech=true;f.submit(a,voice=true);f.complete()
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(a.messages.none{it.contains("no_speech")}&&System.nanoTime()<end)Thread.sleep(5)
        assertTrue(a.messages.any{it.contains("no_speech")});assertFalse(a.messages.any{it.contains("\"type\":\"INTERPRETATION\"")})
    }
    @Test fun secondaryLanguageEliminatesTranslationAndSpeechWork()=Fixture().use { f ->
        val a=f.join("alice","en");val b=f.join("bob",secondary="en");f.submit(a,voice=true);f.complete();f.result(b,"INTERPRETATION")
        assertEquals("en",f.requests.single()["targets"]);assertEquals("",f.requests.single()["audioTargets"])
    }
    @Test fun knownLanguageDoesNotBypassTypedChatTranslation()=Fixture().use { f ->
        val a=f.join("alice","en");val b=f.join("bob",secondary="en");f.submit(a);f.complete();f.result(b,"CHAT_MESSAGE")
        assertTrue(f.requests.single()["targets"].toString().contains("ko"))
    }
    @Test fun progressiveSpeechArrivesBeforeFinalResponse()=Fixture().use { f ->
        f.stream=true;val a=f.join("alice","en");val b=f.join("bob");f.submit(a,voice=true)
        f.result(b,"INTERPRETATION");assertEquals("fixture",parseFlatJsonObject(f.result(b,"INTERPRETATION_AUDIO")).requiredString("audioWav"))
        assertFalse(a.messages.any { it.contains("\"status\":\"complete\"") });f.complete()
    }
    @Test fun progressiveKnownLanguageGetsOriginalCaptionWithoutAudio()=Fixture().use { f ->
        f.stream=true;val a=f.join("alice","en");val b=f.join("bob",secondary="en");f.submit(a,voice=true)
        assertEquals("original",parseFlatJsonObject(f.result(b,"INTERPRETATION")).requiredString("translationStatus"))
        f.complete();assertFalse(b.messages.any { it.contains("\"type\":\"INTERPRETATION_AUDIO\"") })
    }
    @Test fun failureAfterPartialDeliveryIsNotReportedComplete()=Fixture().use { f ->
        f.stream=true;f.fail=true;val a=f.join("alice","en");val b=f.join("bob");f.submit(a,voice=true)
        f.result(b,"INTERPRETATION_AUDIO");f.complete();f.result(a,"ERROR");f.result(b,"INTERPRETATION_STATUS")
        assertFalse(a.messages.any { it.contains("\"status\":\"complete\"") })
    }
}
