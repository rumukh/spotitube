package com.example.spotitube.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ensures that when several requests overlap, exactly one acts — the newest.
 *
 * Deliberately **process-scoped**, not per-activity. Under the `standard` launch mode two different
 * link URIs create two independent `LinkHandlerActivity` instances, each with its own fields, so a
 * per-instance generation counter can only arbitrate between intents delivered to the *same*
 * instance. Across instances neither can see the other and both launch: measured on device, tapping
 * two links ~250 ms apart played whichever resolve happened to finish last, and at ~800 ms the
 * *older* link won three times out of three.
 *
 * Equally deliberately, the coroutine runs in a caller-supplied scope rather than an activity's
 * `lifecycleScope`. The superseded activity calls `finish()`, and if the newest request were tied to
 * a lifecycle it would be killed by the *previous* activity's scope cancellation.
 *
 * Pure Kotlin with injected lambdas so both branches can be forced in a JVM test — the same shape as
 * [LaunchPlan], and for the same reason: a device can prove whichever path happens to occur, but it
 * cannot force a losing race on demand.
 */
class RequestArbiter<T>(private val scope: CoroutineScope) {

  private val newestTicket = AtomicLong(0)
  private val inFlight = AtomicReference<Job?>(null)

  /** The ticket of the most recent submission. Exposed for assertions and logging. */
  val newest: Long
    get() = newestTicket.get()

  /**
   * Claims the newest ticket, cancels any in-flight request, and runs [resolve].
   *
   * Exactly **one** of [onResult], [onSuperseded] or [onFailure] is invoked per submission, on the
   * scope's dispatcher, so a caller can always rely on being told how it ended.
   *
   * @param onResult this request won and produced a result — the only path that may act.
   * @param onSuperseded a newer request exists, or this one was cancelled. Must exit **silently**:
   *   no launch, no search, no toast, and nothing logged as a failure. Being superseded is a normal
   *   outcome of tapping two links, not an error.
   * @param onFailure [resolve] threw something that was not cancellation, and this is still newest.
   */
  @Synchronized
  fun submit(
    resolve: suspend () -> T,
    onResult: (T) -> Unit,
    onSuperseded: () -> Unit,
    onFailure: (Throwable) -> Unit,
  ): Long {
    val ticket = newestTicket.incrementAndGet()

    // Exactly one terminal callback per submission, whichever path gets there first.
    val reported = AtomicBoolean(false)
    fun reportOnce(block: () -> Unit) {
      if (reported.compareAndSet(false, true)) block()
    }

    inFlight.getAndSet(null)?.cancel(CancellationException("superseded by request $ticket"))

    val job =
      scope.launch {
        val result =
          try {
            resolve()
          } catch (cancellation: CancellationException) {
            // Never convert cancellation into a user-facing outcome. Catching it into an
            // `Unsupported` is how a superseded resolve came to toast "Job was cancelled" at the
            // user; `runCatching` swallows it by default, which is the trap this exists to close.
            // Rethrown, and reported by the completion handler below.
            throw cancellation
          } catch (error: Throwable) {
            reportOnce { if (isNewest(ticket)) onFailure(error) else onSuperseded() }
            return@launch
          }

        reportOnce { if (isNewest(ticket)) onResult(result) else onSuperseded() }
      }

    // Guarantees a terminal callback even when the coroutine is cancelled BEFORE its body ever
    // runs — two intents arriving in one main-loop tick would otherwise leave the losing activity
    // with no callback at all, and a translucent one-shot activity that never finishes stays on
    // screen. Dispatched through the scope so the callback lands on the scope's dispatcher rather
    // than whichever thread happened to cancel the job.
    job.invokeOnCompletion { cause ->
      if (cause != null) scope.launch { reportOnce { onSuperseded() } }
    }

    inFlight.set(job)
    return ticket
  }

  private fun isNewest(ticket: Long): Boolean = newestTicket.get() == ticket

  /** Drops any in-flight request without reporting it. Tests only. */
  @Synchronized
  fun reset() {
    inFlight.getAndSet(null)?.cancel(CancellationException("reset"))
    newestTicket.set(0)
  }
}
