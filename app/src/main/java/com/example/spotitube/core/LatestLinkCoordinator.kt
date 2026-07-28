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
 * **The settle window** covers what arbitration provably cannot: latest-wins can only suppress an
 * older request once a newer one **exists**, so if A resolves and launches at 400 ms and B is tapped
 * at 600 ms, no token can undo a side effect that already happened. Only a quiet window can. It
 * defaults to one second and runs *concurrently* with the resolve, so the wait is
 * `max(resolve, window)` and is usually absorbed entirely at the measured ~0.8–1.3 s device latency.
 * The case it guards is ordinary rather than exotic: the loop guard deliberately permits a same-link
 * double tap and trips only on the third hit, so without a window a user who taps again because
 * nothing visibly happened gets two launches. See [DEFAULT_SETTLE_WINDOW_MILLIS].
 *
 * The two mechanisms are kept separable on purpose. `arbitration alone, with the window off` is a
 * standing test at every measured spacing, so the concurrency suite can never be green merely
 * because a timer masked a race — if turning the window off breaks those tests, the arbitration is
 * broken and the window is hiding it.
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
     * **One second.** Arbitration alone covers every *measured* failure — and a zero-window test
     * still proves that, deliberately, so the concurrency suite cannot be green merely because a
     * timer masked a race. The window exists for the case arbitration provably cannot reach:
     *
     * * Latest-wins can only suppress an older request once a newer one **exists**. If A resolves
     *   and launches at 400 ms and B is tapped at 600 ms, no token can undo a side effect that has
     *   already happened. Only a quiet window can.
     * * That case is not exotic. **The loop guard does not coalesce a same-link double tap** — it
     *   deliberately permits two hits and trips only on the third. Tapping twice because nothing
     *   visibly happened for a second is the *ordinary* rapid interaction, not a contrived one.
     * * A second launch is not cosmetic. The older request audibly plays, mutates YouTube Music's
     *   queue, history and notification state, may interrupt casting, and can remain the effective
     *   result if the newer resolve later degrades to SEARCH. For an app whose whole promise is one
     *   exact recording, letting a wrong one play first contradicts the promise.
     *
     * **Why 1,000 and not less.** Wrong-order bursts were measured on device through ~800 ms
     * spacings; 1,000 ms adds ~200 ms of scheduling and device headroom. A shorter 300–400 ms
     * compromise was rejected on principle: it knowingly fails the measured 500 ms and 800 ms
     * cases, and "mostly covers" is not a correctness boundary.
     *
     * **What it costs, honestly.** The window runs *concurrently* with the resolve and the wait is
     * `max(resolve, window)`, so at the measured device latency of ~0.8–1.3 s it is usually free.
     * It is not free on a warm connection: a 400 ms resolve now waits the full second. That is a
     * real regression on the common single-tap path, accepted deliberately in exchange for never
     * playing the wrong recording first. The spinner gives immediate feedback meanwhile.
     *
     * Treat this as a conservative **initial** value, not dogma. It may be reduced with device
     * evidence across warm and cold, wifi and mobile — but never below the largest burst interval
     * we promise to coalesce. Do not add telemetry for it.
     */
    const val DEFAULT_SETTLE_WINDOW_MILLIS = 1_000L
  }
}
