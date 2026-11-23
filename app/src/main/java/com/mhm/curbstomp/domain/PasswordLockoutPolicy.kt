package com.mhm.curbstomp.domain

/**
 * Pure exponential brute-force lockout policy for Strict Mode password verification.
 *
 * Allows [MAX_FREE_ATTEMPTS] consecutive failures without delay, then applies exponential
 * backoff from [BASE_LOCKOUT_MS] up to [MAX_LOCKOUT_MS]. State can be persisted across
 * process restarts via [LockoutState].
 */
object PasswordLockoutPolicy {

    const val MAX_FREE_ATTEMPTS = 3
    const val BASE_LOCKOUT_MS = 3_000L
    const val MAX_LOCKOUT_MS = 60_000L

    data class LockoutState(
        val failedAttempts: Int = 0,
        val lockoutUntilWallMs: Long = 0L
    )

    data class FailureOutcome(
        val nextState: LockoutState,
        val lockoutSeconds: Long
    )

    /**
     * Returns remaining lockout duration in whole seconds (rounded up), or `0` if unlocked.
     * Caps remaining time at [MAX_LOCKOUT_MS] so a backward wall-clock jump can never cause
     * an unbounded lockout.
     */
    fun remainingLockoutSeconds(
        state: LockoutState,
        nowWallMs: Long,
        lockoutUntilElapsedMs: Long = 0L,
        nowElapsedMs: Long = 0L
    ): Long {
        val wallRemaining = (state.lockoutUntilWallMs - nowWallMs)
            .coerceIn(0L, MAX_LOCKOUT_MS)
        val elapsedRemaining = if (lockoutUntilElapsedMs > 0L && nowElapsedMs > 0L) {
            (lockoutUntilElapsedMs - nowElapsedMs).coerceIn(0L, MAX_LOCKOUT_MS)
        } else {
            0L
        }
        val effectiveRemaining = maxOf(wallRemaining, elapsedRemaining)
        if (effectiveRemaining <= 0L) return 0L
        return (effectiveRemaining + 999L) / 1000L
    }

    fun computeLockoutMillis(failedAttempts: Int): Long {
        if (failedAttempts < MAX_FREE_ATTEMPTS) return 0L
        val exponent = (failedAttempts - MAX_FREE_ATTEMPTS).coerceAtMost(5)
        return (BASE_LOCKOUT_MS shl exponent).coerceAtMost(MAX_LOCKOUT_MS)
    }

    fun recordFailure(state: LockoutState, nowWallMs: Long): FailureOutcome {
        val nextAttempts = state.failedAttempts + 1
        val lockoutMs = computeLockoutMillis(nextAttempts)
        val nextUntil = if (lockoutMs > 0L) nowWallMs + lockoutMs else 0L
        return FailureOutcome(
            nextState = LockoutState(
                failedAttempts = nextAttempts,
                lockoutUntilWallMs = nextUntil
            ),
            lockoutSeconds = lockoutMs / 1000L
        )
    }

    fun recordSuccess(): LockoutState = LockoutState(failedAttempts = 0, lockoutUntilWallMs = 0L)
}
