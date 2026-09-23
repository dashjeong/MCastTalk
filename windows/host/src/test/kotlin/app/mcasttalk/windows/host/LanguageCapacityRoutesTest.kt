package app.mcasttalk.windows.host

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test

class LanguageCapacityRoutesTest {
    @Test fun authenticatedReadAdminMutationAndExplicitDelayAcknowledgement()=withAccountRoot { root ->
        WorkspaceSetup.initialize(root)
        TestAccountFixture(root).use { fixture ->
            val admin=fixture.adminCookie();val user=fixture.cookie()
            testApplication {
                application { mcastTalkModule(root,accountServices=fixture.services) }
                assertEquals(401,client.get("/api/v1/language-capacity"){headers.append(HttpHeaders.Host,"localhost")}.status.value)
                val read=client.get("/api/v1/language-capacity"){headers.append(HttpHeaders.Host,"localhost");headers.append(HttpHeaders.Cookie,user)}
                assertEquals(200,read.status.value);assertTrue(read.bodyAsText().contains("\"calibrationStatus\":\"pending\""))
                suspend fun change(cookie:String,body:String,origin:String="http://localhost")=client.post("/api/v1/admin/language-capacity") {
                    headers.append(HttpHeaders.Host,"localhost");headers.append(HttpHeaders.Cookie,cookie);headers.append(HttpHeaders.Origin,origin)
                    headers.append(HttpHeaders.ContentType,"application/json");setBody(body)
                }
                val override="{\"languages\":\"ko,en\",\"allowDelay\":true,\"acknowledgeDelay\":true}"
                assertEquals(403,change(user,override).status.value)
                assertEquals(403,change(admin,override,"https://attacker.invalid").status.value)
                assertEquals(400,change(admin,"{\"languages\":\"ko\",\"allowDelay\":true}").status.value)
                assertEquals(200,change(admin,"{\"languages\":\"ko,en\",\"meetingLanguageLimit\":2}").status.value)
                assertEquals(400,change(admin,"{\"languages\":\"ko,en\",\"meetingLanguageLimit\":1}").status.value)
                assertEquals(200,change(admin,override).status.value)
                assertEquals(200,change(admin,"{\"languages\":\"\",\"allowDelay\":false}").status.value)
                val result=client.get("/api/v1/language-capacity"){headers.append(HttpHeaders.Host,"localhost");headers.append(HttpHeaders.Cookie,user)}.bodyAsText()
                assertTrue(result.contains("\"mode\":\"quality-first\""));assertFalse(result.contains(root.toString()))
            }
        }
    }
}
