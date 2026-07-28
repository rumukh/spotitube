package com.example.spotitube.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Overlapping-link arbitration, driven on **virtual time** with **two distinct simulated owners**.
 *
 * The bug these cover was found on a phone and is not reproducible on demand there: at 250–500 ms
 * both taps launched and the winner was whichever network call returned last, and at ~800 ms the
 * *older* link won three times out of three. A device can only demonstrate whichever race happens
 * to occur. Gating each resolve on a [CompletableDeferred] and advancing a test scheduler lets the
 * test choose the interleaving — including the ones that lost on hardware.
 *
 * "Two owners" matters: the defect lived precisely in the case of two independent Activity
 * *instances*, which per-instance state could never arbitrate, and which an `onNewIntent`-only test
 * cannot express.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestLinkCoordinatorTest {

  /** Stands in for one Activity instance: awaits its own ticket, acts only if still current. */
  private class Owner(val label: String) {
    var outcome: String? = null
    var launched: String? = null
    var finished = false
    var waiter: Job? = null
  }

  private class Harness(val scope: TestScope, settleWindowMillis: Long = 500L) {
    val gates = mutableMapOf<String, CompletableDeferred<String>>()
    val launches = mutableListOf<String>()
    val failureLogs = mutableListOf<String>()

    val coordinator =
      LatestLinkCoordinator<String>(
        scope = scope,
        settleWindowMillis = settleWindowMillis,
        resolve = { input -> gates.getOrPut(input!!) { CompletableDeferred() }.await() },
      )

    /** Submits as a distinct owner and returns it, mimicking a fresh Activity instance. */
    fun submit(input: String): Owner {
      val owner = Owner(input)
      val ticket = coordinator.submit(input)
      owner.waiter =
        scope.launch {
          when (val result = ticket.await()) {
            is LinkRequestOutcome.Superseded -> owner.outcome = "superseded"
            is LinkRequestOutcome.Resolved -> {
              owner.outcome = "resolved"
              coordinator.consumeIfCurrent(ticket) {
                owner.launched = result.value
                launches += result.value
              }
            }
            is LinkRequestOutcome.Failed -> {
              owner.outcome = "failed"
              failureLogs += "${owner.label}:${result.error.javaClass.simpleName}"
            }
          }
          owner.finished = true
        }
      return owner
    }

    fun resolveNow(input: String, value: String = input) {
      gates.getOrPut(input) { CompletableDeferred() }.complete(value)
    }
  }

  // --- 1. A then B at several spacings ----------------------------------------------------------

  @Test
  fun `inside the settle window B wins with exactly one launch`() {
    for (gap in listOf(0L, 100L, 250L, 400L)) {
      runTest {
        val h = Harness(TestScope(testScheduler))
        val a = h.submit("A")
        // A resolves quickly — the hardware case where the older link launched first.
        h.resolveNow("A")
        testScheduler.advanceTimeBy(gap)

        val b = h.submit("B")
        h.resolveNow("B")
        testScheduler.advanceUntilIdle()

        assertEquals("gap=$gap: A must be superseded", "superseded", a.outcome)
        assertEquals("gap=$gap: B must resolve", "resolved", b.outcome)
        assertEquals("gap=$gap: exactly one launch", listOf("B"), h.launches)
        assertEquals("gap=$gap: and it is B's payload", "B", b.launched)
      }
    }
  }

  @Test
  fun `beyond the settle window both act, and the newer one is last`() = runTest {
    // Documents the limit of the current window rather than hiding it. At 800ms A has already
    // resolved, waited out its window and launched before B exists — and nothing can undo a launch
    // that already happened. The user sees a brief flicker and lands on B, which is the correct
    // song; this is a cosmetic cost, not the wrong-song defect.
    //
    // Raising DEFAULT_SETTLE_WINDOW_MILLIS above the spacing would collapse this to a single
    // launch, at the price of adding dead time to every single tap on a fast connection. That is
    // the trade the constant documents.
    val h = Harness(TestScope(testScheduler), settleWindowMillis = 500L)
    val a = h.submit("A")
    h.resolveNow("A")
    testScheduler.advanceTimeBy(800)

    val b = h.submit("B")
    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals("both act at this spacing", listOf("A", "B"), h.launches)
    assertEquals("but the newer link is last, so it is what plays", "B", h.launches.last())
    assertEquals("resolved", a.outcome)
    assertEquals("resolved", b.outcome)
  }

  @Test
  fun `a longer window would collapse the 800ms case to one launch`() = runTest {
    // Evidence for the settle-window ruling: the same 800ms burst, with a 1000ms window.
    val h = Harness(TestScope(testScheduler), settleWindowMillis = 1_000L)
    val a = h.submit("A")
    h.resolveNow("A")
    testScheduler.advanceTimeBy(800)

    val b = h.submit("B")
    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals(listOf("B"), h.launches)
    assertEquals("superseded", a.outcome)
  }

  // --- 2. a resolver that ignores cancellation and returns late ---------------------------------

  @Test
  fun `a resolver that ignores cancellation still cannot act`() = runTest {
    val launches = mutableListOf<String>()
    val slowGate = CompletableDeferred<String>()
    val coordinator =
      LatestLinkCoordinator<String>(
        scope = TestScope(testScheduler),
        settleWindowMillis = 500L,
        resolve = { input ->
          if (input == "A") {
            // Deliberately uncooperative: ignores cancellation for a while, then returns late.
            withContext(NonCancellable) {
              delay(2_000)
              "A"
            }
          } else {
            slowGate.await()
          }
        },
      )

    val ticketA = coordinator.submit("A")
    val a = launch {
      if (ticketA.await() is LinkRequestOutcome.Resolved) {
        coordinator.consumeIfCurrent(ticketA) { launches += "A" }
      }
    }
    testScheduler.advanceTimeBy(100)

    val ticketB = coordinator.submit("B")
    val b = launch {
      if (ticketB.await() is LinkRequestOutcome.Resolved) {
        coordinator.consumeIfCurrent(ticketB) { launches += "B" }
      }
    }
    slowGate.complete("B")
    testScheduler.advanceUntilIdle()
    a.join()
    b.join()

    assertEquals("the generation consume must block a late, uncancellable A", listOf("B"), launches)
  }

  // --- 3. cancellation is never a user-facing failure --------------------------------------------

  @Test
  fun `cancellation produces no failure and no result`() = runTest {
    val h = Harness(TestScope(testScheduler))
    val a = h.submit("A") // never resolved
    testScheduler.advanceTimeBy(100)
    val b = h.submit("B")
    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals("superseded", a.outcome)
    assertEquals("cancellation must not surface as a failure", emptyList<String>(), h.failureLogs)
    assertEquals(listOf("B"), h.launches)
    assertTrue("the loser must still be released", a.finished)
    assertEquals("resolved", b.outcome)
  }

  // --- 4. the old owner finishes AFTER B submits -------------------------------------------------

  @Test
  fun `B survives the older owner finishing`() = runTest {
    val h = Harness(TestScope(testScheduler))
    val a = h.submit("A")
    testScheduler.advanceTimeBy(100)
    val b = h.submit("B")

    // The superseded Activity tears down — cancelling its own waiter, which must NOT touch the
    // central resolve job for B.
    a.waiter?.cancel()
    testScheduler.advanceTimeBy(50)

    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals("B must still resolve and act", "resolved", b.outcome)
    assertEquals(listOf("B"), h.launches)
  }

  // --- 5. loop guard invalidates central work before acting alone --------------------------------

  @Test
  fun `supersedeAll stops in-flight work from acting afterwards`() = runTest {
    val h = Harness(TestScope(testScheduler))
    val a = h.submit("A")

    // The loop-guard escape hatch: invalidate first, then act alone.
    h.coordinator.supersedeAll()
    h.resolveNow("A")
    testScheduler.advanceUntilIdle()

    assertEquals("superseded", a.outcome)
    assertEquals("an older central job must not launch after the guard acted", emptyList<String>(), h.launches)
  }

  // --- 6. the settle window ----------------------------------------------------------------------

  @Test
  fun `an isolated request delivers after the settle window`() = runTest {
    val h = Harness(TestScope(testScheduler), settleWindowMillis = 500L)
    val a = h.submit("A")
    h.resolveNow("A") // resolves immediately; the window has not elapsed

    testScheduler.advanceTimeBy(400)
    assertEquals("must not act before the window closes", emptyList<String>(), h.launches)

    testScheduler.advanceTimeBy(200)
    assertEquals(listOf("A"), h.launches)
    assertEquals("resolved", a.outcome)
  }

  @Test
  fun `a second tap inside the window suppresses the first entirely`() = runTest {
    // The case arbitration alone provably cannot fix: without the window, A would already have
    // launched before B existed, and no token can undo a launch that has happened.
    val h = Harness(TestScope(testScheduler), settleWindowMillis = 500L)
    val a = h.submit("A")
    h.resolveNow("A")
    testScheduler.advanceTimeBy(300)

    val b = h.submit("B")
    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals("A must never have launched", listOf("B"), h.launches)
    assertEquals("superseded", a.outcome)
  }

  @Test
  fun `two taps beyond the window are two separate actions`() = runTest {
    val h = Harness(TestScope(testScheduler), settleWindowMillis = 500L)
    h.submit("A")
    h.resolveNow("A")
    testScheduler.advanceUntilIdle()

    h.submit("B")
    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals("beyond the burst window these are deliberate, separate taps", listOf("A", "B"), h.launches)
  }

  @Test
  fun `the settle window is absorbed when resolving takes longer`() = runTest {
    val h = Harness(TestScope(testScheduler), settleWindowMillis = 500L)
    h.submit("A")
    // Resolve returns at 900ms, well past the window, so the window adds no delay of its own.
    testScheduler.advanceTimeBy(900)
    h.resolveNow("A")
    testScheduler.advanceTimeBy(1)

    assertEquals("no dead time when the resolve already outlasts the window", listOf("A"), h.launches)
  }

  // --- consume atomicity -------------------------------------------------------------------------

  @Test
  fun `consumeIfCurrent runs once and only for the newest generation`() = runTest {
    val h = Harness(TestScope(testScheduler))
    val ticket = h.coordinator.submit("A")
    var runs = 0

    assertTrue(h.coordinator.consumeIfCurrent(ticket) { runs++ })
    assertFalse("a second consume of the same ticket must not run", h.coordinator.consumeIfCurrent(ticket) { runs++ })

    h.coordinator.submit("B")
    assertFalse("a stale ticket must not run", h.coordinator.consumeIfCurrent(ticket) { runs++ })
    assertEquals(1, runs)
  }

  @Test
  fun `a genuine failure is reported as Failed, not Superseded`() = runTest {
    val coordinator =
      LatestLinkCoordinator<String>(
        scope = TestScope(testScheduler),
        settleWindowMillis = 0L,
        resolve = { throw java.io.IOException("boom") },
      )
    val ticket = coordinator.submit("A")
    var seen: String? = null
    launch { seen = ticket.await()::class.simpleName }
    testScheduler.advanceUntilIdle()

    assertEquals("Failed", seen)
  }
}
