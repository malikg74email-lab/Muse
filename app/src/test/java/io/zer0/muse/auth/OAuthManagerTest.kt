package io.zer0.muse.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthManagerTest {

    @Test
    fun authorizationCodeWaitTimesOutInsteadOfHangingForever() = runTest {
        val result = OAuthManager.awaitAuthorizationCode(
            deferred = CompletableDeferred(),
            timeoutMs = 1L,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("timed out"))
    }

    @Test
    fun authorizationCodeWaitPropagatesExternalCancellation() = runTest {
        val job = launch {
            OAuthManager.awaitAuthorizationCode(
                deferred = CompletableDeferred(),
                timeoutMs = 60_000L,
            )
        }

        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }
}
