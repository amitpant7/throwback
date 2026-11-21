package app.throwback.timelimit.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BusyActionTest {

    @Test
    fun `a quick second tap does nothing, and running ends with the work`() = runTest {
        val action = BusyAction(this) // Like the screen's scope: the coroutine starts later, not at once.
        var checks = 0
        val done = CompletableDeferred<Unit>()
        action.run {
            checks++
            done.await()
        }
        action.run { checks++ } // Before the first one's coroutine has even started.
        assertTrue(action.running)
        runCurrent()
        assertEquals(1, checks)
        done.complete(Unit)
        runCurrent()
        assertFalse(action.running)
    }

    @Test
    fun `running ends when the screen goes away midway`() = runTest {
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val action = BusyAction(screen)
        action.run { awaitCancellation() }
        runCurrent()
        assertTrue(action.running)
        screen.cancel()
        runCurrent()
        assertFalse(action.running)
    }
}
