package com.example.spotitube.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How a submitted link request ended. Exactly one of these per ticket. */
sealed interface LinkRequestOutcome<out T> {
  /** A newer request exists, or this one was cancelled. Must be handled **silently**. */
  data object Superseded : LinkRequestOutcome<Nothing>

  data class Resolved<T>(val value: T) : LinkRequestOutcome<T>

  /** A genuine error — never cancellation. */
  data class Failed(val error: Throwable) : LinkRequestOutcome<Nothing>
}

/**
 * A claim on one submitted request. The caller awaits its own ticket; the coordinator never holds a
 * reference back to the caller, so an Activity cannot be leaked into the process singleton.
 */
class LinkTicket<T> internal constructor(
  val generation: Long,
  private val completion: CompletableDeferred<LinkRequestOutcome<T>>,
) {
  suspend fun await(): LinkRequestOutcome<T> = completion.await()
}

/**
 * Decides which of several overlapping link requests is allowed to act.
 *
 * Two separate problems, and they need two separate mechanisms:
 *
 * **Arbitration** handles a newer request arriving while an older one is still resolving. Under the
 * default `standard` launch mode two different link URIs create two independent activity instances,
 * so this has to be process-scoped: per-instance state cannot see across instances, and measured on
 * device both launched, with the *older* link winning 3 of 3 at ~800 ms spacing.
 *
 * **The settle window** is a mechanism kept for one hypothetical arbitration cannot cover: an old
 * resolve finishing and launching at 700 ms with the next tap landing at 800 ms, where no token can
 * undo a launch that already happened. **Its default is zero, because that case has never been
 * observed.** Every measured failure — 250 ms, 500 ms and 800 ms spacings alike — had the newer
 * request submitted while the older was still resolving, which is precisely what arbitration fixes.
 * The device trace is unambiguous: `INPUT` A 09.432, `INPUT` B 09.683, B result 10.466, stale A
 * result 10.766 — B existed **1,083 ms** before A's side effect. A non-zero default would tax the
 * overwhelmingly common single-tap path to prevent a failure no measurement has produced, so
 * [settleWindowMillis] stays injectable and tested, and stays off.
 *
 * The resolve job deliberately runs in [scope] and is **not** a child of any Activity: the older
 * instance calls `finish()`, and a lifecycle-tied job for the *newest* request would be killed by
 * the *previous* activity's scope cancellation.
 */
class LatestLinkCoordinator<T>(
  private val scope: CoroutineScope,
  private val resolve: suspend (String?) -> T,
  private val settleWindowMillis: Long = DEFAULT_SETTLE_WINDOW_MILLIS,
) {

  private val lock = Any()
  private var newestGeneration = 0L
  private var consumedGeneration = -1L
  private var inFlight: Job? = null
  private var pending: CompletableDeferred<LinkRequestOutcome<T>>? = null

  /**
   * Supersedes any prior request and starts a new one.
   *
   * Returns immediately with a ticket; the caller awaits it in its own scope.
   */
  fun submit(input: String?): LinkTicket<T> {
    val completion = CompletableDeferred<LinkRequestOutcome<T>>()
    val generation: Long

    synchronized(lock) {
      generation = ++newestGeneration
      // Complete the prior ticket first, so its owner is released even if its job had not started.
      pending?.complete(LinkRequestOutcome.Superseded)
      inFlight?.cancel(CancellationException("superseded by generation $generation"))
      pending = completion
      inFlight = null
    }

    val job =
      scope.launch {
        try {
          val value =
            if (settleWindowMillis <= 0L) {
              // The default. No dispatch, no join, nothing on the common path.
              resolve(input)
            } else {
              coroutineScope {
                // Runs CONCURRENTLY with the resolve, so it costs nothing whenever resolving
                // already takes longer than the window — which is the measured norm.
                val settle = launch { delay(settleWindowMillis) }
                val resolved = resolve(input)
                settle.join()
                resolved
              }
            }
          synchronized(lock) { if (newestGeneration == generation) pending = null }
          completion.complete(LinkRequestOutcome.Resolved(value))
        } catch (cancellation: CancellationException) {
          // Caught separately and reported ONLY as Superseded. Converting cancellation into a
          // user-facing outcome is what produced a "Job was cancelled" toast and a spurious SEARCH.
          completion.complete(LinkRequestOutcome.Superseded)
          throw cancellation
        } catch (error: Throwable) {
          synchronized(lock) { if (newestGeneration == generation) pending = null }
          completion.complete(LinkRequestOutcome.Failed(error))
        }
      }

    synchronized(lock) { if (newestGeneration == generation) inFlight = job }
    // Drop the finished job rather than letting the process-scoped singleton pin the last Job and
    // its whole callback graph until the next submission. Identity-checked so a later submission's
    // job is never cleared by an earlier one completing.
    job.invokeOnCompletion { synchronized(lock) { if (inFlight === job) inFlight = null } }
    return LinkTicket(generation, completion)
  }

  /**
   * Runs [block] only if [ticket] is still the newest request and nothing has acted for it yet.
   *
   * The atomic check is what stops a resolve that completed moments before a newer submission from
   * acting afterwards — the await and the action are not the same instant.
   *
   * @return true if [block] ran.
   */
  fun consumeIfCurrent(ticket: LinkTicket<T>, block: () -> Unit): Boolean {
    synchronized(lock) {
      if (ticket.generation != newestGeneration || consumedGeneration == ticket.generation) return false
      consumedGeneration = ticket.generation
    }
    block()
    return true
  }

  /** Invalidates any in-flight work so a caller can act alone. Used by the loop-guard escape hatch. */
  fun supersedeAll() {
    synchronized(lock) {
      newestGeneration++
      pending?.complete(LinkRequestOutcome.Superseded)
      pending = null
      inFlight?.cancel(CancellationException("superseded by loop guard"))
      inFlight = null
    }
  }

  companion object {
    /**
     * How long the app waits for things to go quiet before acting on a resolved link.
     *
     * **Zero.** Not "no window was considered" — a window was built, measured against the device
     * evidence, and switched off because the evidence does not support paying for it:
     *
     * * Every observed failure (250 ms, 500 ms and 800 ms spacings) had the newer request submitted
     *   **while the older was still resolving**. Process-scoped latest-wins arbitration fixes all of
     *   them with no added latency. Measured resolve is ~0.8–1.3 s, far wider than any tap gap a
     *   human produces.
     * * The case a window would fix — an older resolve completing and launching *before* the next
     *   tap exists — has **never been observed**. Once A has completed and switched apps, B is
     *   reasonably a separate action anyway.
     * * A non-zero default therefore taxes the overwhelmingly common single-tap path to prevent a
     *   failure no measurement has produced.
     *
     * The parameter stays injectable and a test still proves a synthetic non-zero value coalesces a
     * future-arrival request, so the mechanism is exercised and re-enabling it is a one-line change
     * if evidence ever appears. Until then the resolve path skips it entirely — no dispatch, no join.
     *
     * **Do not raise this without a device trace showing a second launch that arbitration missed.**
     * The concurrency tests must pass because resolvers are held *overlapping*, never because a
     * timer masked the race; if a window is what makes them green, the arbitration is broken and the
     * window is hiding it.
     */
    const val DEFAULT_SETTLE_WINDOW_MILLIS = 0L
  }
}
