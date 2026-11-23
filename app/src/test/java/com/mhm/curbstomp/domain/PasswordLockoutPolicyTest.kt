package com.mhm.curbstomp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordLockoutPolicyTest {

    @Test
    fun firstTwoFailuresAreFreeAndThirdTriggersExponentialBackoffUpToCap() {
        val now = 1_000_000L
        var state = PasswordLockoutPolicy.LockoutState()

        val f1 = PasswordLockoutPolicy.recordFailure(state, now)
        assertEquals(0L, f1.lockoutSeconds)
        assertEquals(1, f1.nextState.failedAttempts)
        state = f1.nextState

        val f2 = PasswordLockoutPolicy.recordFailure(state, now)
        assertEquals(0L, f2.lockoutSeconds)
        assertEquals(2, f2.nextState.failedAttempts)
        state = f2.nextState

        // 3rd -> 3s, 4th -> 6s, 5th -> 12s, 6th -> 24s, 7th -> 48s, 8th -> 60s (capped)
        val expectedSeconds = listOf(3L, 6L, 12L, 24L, 48L, 60L, 60L)
        for (expected in expectedSeconds) {
            val outcome = PasswordLockoutPolicy.recordFailure(state, now)
            assertEquals(expected, outcome.lockoutSeconds)
            assertEquals(now + expected * 1000L, outcome.nextState.lockoutUntilWallMs)
            state = outcome.nextState
        }
    }

    @Test
    fun remainingLockoutRoundsUpAndCapsBackwardWallClockJumps() {
        val state = PasswordLockoutPolicy.LockoutState(
            failedAttempts = 3,
            lockoutUntilWallMs = 1_003_000L
        )
        assertEquals(3L, PasswordLockoutPolicy.remainingLockoutSeconds(state, 1_000_000L))
        assertEquals(1L, PasswordLockoutPolicy.remainingLockoutSeconds(state, 1_002_999L))
        assertEquals(0L, PasswordLockoutPolicy.remainingLockoutSeconds(state, 1_003_000L))

        // If device clock jumps backward by a day, lockout is still bounded at MAX_LOCKOUT_MS (60s)
        assertEquals(60L, PasswordLockoutPolicy.remainingLockoutSeconds(state, 100_000L))
    }

    @Test
    fun monotonicElapsedClockBlocksForwardWallClockBypass() {
        val state = PasswordLockoutPolicy.LockoutState(
            failedAttempts = 4,
            lockoutUntilWallMs = 1_006_000L
        )
        // Wall clock jumped forward past lockoutUntilWallMs, but elapsedRealtime still has 4.2s left
        val secs = PasswordLockoutPolicy.remainingLockoutSeconds(
            state = state,
            nowWallMs = 2_000_000L,
            lockoutUntilElapsedMs = 50_000L,
            nowElapsedMs = 45_800L
        )
        assertEquals(5L, secs)
    }

    @Test
    fun recordSuccessResetsAttemptsAndDeadline() {
        val cleared = PasswordLockoutPolicy.recordSuccess()
        assertEquals(0, cleared.failedAttempts)
        assertEquals(0L, cleared.lockoutUntilWallMs)
        assertEquals(0L, PasswordLockoutPolicy.remainingLockoutSeconds(cleared, 1_000_000L))
    }
}
