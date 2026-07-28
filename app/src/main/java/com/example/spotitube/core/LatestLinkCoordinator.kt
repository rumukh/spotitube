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
 * **The settle window** handles what arbitration provably cannot. Latest-wins can only suppress an
 * older request once a newer one *exists* — if the old resolve finishes and launches at 700 ms and
 * the next tap lands at 800 ms, no token can undo a launch that already happened. Only refusing to
 * act until things have been quiet for a moment can. This is why [settleWindowMillis] exists rather
 * than acting the instant a resolve returns.
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
            coroutineScope {
              // The quiet window runs CONCURRENTLY with the resolve, so it costs nothing whenever
              // resolving already takes longer than the window — which is the measured norm.
              val settle = launch { delay(settleWindowMillis) }
              val resolved = resolve(input)
              settle.join()
              resolved
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
     * **500 ms**, chosen from the device measurements rather than picked:
     *
     * * It covers the whole 250–500 ms band in which two taps both launched.
     * * It runs concurrently with the resolve, so it costs `max(0, 500 ms − resolveTime)`, not
     *   500 ms. Measured resolve on the test phone is ~1.0 s, so on that device it is **free**.
     * * A 1,000 ms window was considered and rejected: on a warm connection returning in 400–600 ms
     *   it would add up to 600 ms of dead time to the overwhelmingly common **single-tap** path, to
     *   prevent a brief flicker in a burst that requires two *different* links tapped inside a
     *   second. Taxing the common path to tidy the rare one is the wrong trade.
     *
     * Note what this does and does not fix. The window is what suppresses the 250–500 ms
     * "both launch" case. The ~800 ms case is fixed by arbitration alone — the newer request simply
     * resolves second and wins — so lengthening the window buys nothing there.
     *
     * Deliberately a named, injectable constant: changing it is a one-line change, not a rework.
     */
    const val DEFAULT_SETTLE_WINDOW_MILLIS = 500L
  }
}
