package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaServiceBindingStateTest {
    @Test
    fun lateOldCallbackCannotReplaceOrReleaseNewConnection() {
        val state = GemmaServiceBindingState<Any, Any>()
        val oldConnection = Any()
        val newConnection = Any()
        val oldService = Any()
        val newService = Any()

        assertTrue(state.install(oldConnection))
        assertTrue(state.publishIfOwned(oldConnection, oldService, accepting = true))
        assertTrue(state.release(oldConnection).owned)

        assertTrue(state.install(newConnection))
        assertTrue(state.publishIfOwned(newConnection, newService, accepting = true))
        assertFalse(state.publishIfOwned(oldConnection, oldService, accepting = true))
        assertFalse(state.release(oldConnection).owned)

        val active = state.snapshot()
        assertSame(newConnection, active.connection)
        assertSame(newService, active.service)
    }

    @Test
    fun cancelledConnectionCannotPublishAndOwnerReleaseClearsState() {
        val state = GemmaServiceBindingState<Any, Any>()
        val connection = Any()

        assertTrue(state.install(connection))
        assertFalse(state.publishIfOwned(connection, Any(), accepting = false))
        assertTrue(state.release(connection).owned)

        val cleared = state.snapshot()
        assertNull(cleared.connection)
        assertNull(cleared.service)
    }
}
