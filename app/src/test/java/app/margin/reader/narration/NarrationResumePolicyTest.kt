package app.margin.reader.narration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lookup gate is the ownership rule shared by ReaderActivity and playback: only a lookup that
 * paused active playback may resume it, and manual controls or navigation invalidate that claim.
 */
class NarrationResumePolicyTest {

    @Test fun anAlreadyPausedReaderGivesLookupNoResumeClaim() {
        val policy = NarrationResumePolicy()

        assertFalse(policy.beginLookupPause(active = true, playing = false))
        assertFalse("closing the lookup must not start an already paused reader", policy.claimLookupResume())
    }

    @Test fun lookupOwnsExactlyOneResumeOfThePauseItCaused() {
        val policy = NarrationResumePolicy()

        assertTrue(policy.beginLookupPause(active = true, playing = true))
        assertTrue(policy.claimLookupResume())
        assertFalse("a stale close cannot resume a second time", policy.claimLookupResume())
    }

    @Test fun aManualPauseOrPlayTakesOwnershipFromTheOpenLookup() {
        val policy = NarrationResumePolicy()
        assertTrue(policy.beginLookupPause(active = true, playing = true))

        policy.clearLookupPause()

        assertFalse("the user's manual control wins over an automatic lookup resume", policy.claimLookupResume())
    }

    @Test fun navigationStopOrEndInvalidatesBothTheLookupClaimAndTheAudioCheckpoint() {
        val policy = NarrationResumePolicy()
        val generation = policy.checkpointGeneration()
        assertTrue(policy.beginLookupPause(active = true, playing = true))

        policy.invalidateCheckpoint()

        assertTrue("a new reading intent advances the checkpoint generation", policy.checkpointGeneration() != generation)
        assertFalse("next, previous, go, retry, stop and end never auto-resume", policy.claimLookupResume())
    }
}
