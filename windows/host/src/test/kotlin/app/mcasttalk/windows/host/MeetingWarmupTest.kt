package app.mcasttalk.windows.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MeetingWarmupTest {
    @Test fun loadingRejectsVoiceWithoutAnUncaughtStateException() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val directory=RoomDirectory();val hub=RoomSocketHub();val membership=RoomMembershipCoordinator(directory,hub)
        val engine=object:MeetingInference {
            override val needsWarmup=true
            override fun warmup():FlatJsonObject { entered.countDown();release.await(5,TimeUnit.SECONDS);return parseFlatJsonObject("{\"status\":\"ready\"}") }
            override fun execute(request:Map<String,Any?>):FlatJsonObject=error("Not expected")
            override fun close(){release.countDown()}
        }
        val interpreter=MeetingInterpreter(engine,membership,scope)
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val person=Participant("alice","Alice",ParticipantPreferences.create("ko",listenLanguage="ko"))
            val connection=membership.join("test-room",person){hub.attach("test-room",person.id,scope,{},{},presenceId=person.presenceId)}
            assertThrows(IllegalArgumentException::class.java){interpreter.submit("test-room",person,connection,voice=VoiceUtterance(1,ByteArray(8000)))}
            assertEquals(1,membership.statistics()["joinedConnections"])
        } finally { release.countDown();interpreter.close();scope.cancel() }
    }
}
