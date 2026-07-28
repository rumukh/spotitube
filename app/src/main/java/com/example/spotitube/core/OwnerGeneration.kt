package com.example.spotitube.core

/**
 * Decides which of one Activity instance's own callbacks is still entitled to act.
 *
 * [LatestLinkCoordinator] arbitrates between *requests*, process-wide. That is not the same
 * question as which *owner* may act, and the gap between the two is a real, user-visible defect:
 *
 * A single `LinkHandlerActivity` instance can receive two links, because an identical intent aimed
 * at the top-most instance is delivered to `onNewIntent` rather than starting a new instance. Both
 * requests then await in the **same** `lifecycleScope`. Without this guard:
 *
 * 1. A is submitted, B arrives via `onNewIntent` and supersedes it;
 * 2. A's ticket completes `Superseded`, and A's callback calls `finish()`;
 * 3. `finish()` destroys the Activity, which cancels the `lifecycleScope`;
 * 4. B's waiter — in that same scope — is cancelled and never consumes its result.
 *
 * B's process-scoped work still resolves correctly. Nobody is left to act on it, so **tapping the
 * same link twice plays nothing at all.** That B resolves fine is the tell: process arbitration was
 * never the missing piece, ownership was.
 *
 * Across two *distinct* Activity instances the older instance's generation is still current for
 * itself, so it finishes correctly and is not stranded on screen. That is why this is a per-instance
 * counter and not a second process-wide one.
 */
class OwnerGeneration {

  private var current = 0L

  /** Claims ownership for a newly arriving request. Call this *before* submitting it. */
  fun next(): Long = ++current

  /** True while [generation] is still the most recent request this owner accepted. */
  fun isCurrent(generation: Long): Boolean = generation == current
}
