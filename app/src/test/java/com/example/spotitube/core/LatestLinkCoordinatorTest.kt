package com.example.spotitube.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

  private class Harness(
    val scope: TestScope,
    settleWindowMillis: Long = LatestLinkCoordinator.DEFAULT_SETTLE_WINDOW_MILLIS,
  ) {
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
  fun `overlapping taps yield one launch at every measured spacing, ARBITRATION ALONE`() {
    // THE DEVICE CASE, with the settle window explicitly OFF.
    //
    // This is pinned to zero deliberately and must stay that way. Every measured failure had B
    // submitted while A was STILL RESOLVING: device trace INPUT A 09.432, INPUT B 09.683, B result
    // 10.466, stale A result 10.766 — B existed 1,083 ms before A's side effect. Arbitration is what
    // fixes that, and with no timer running there is nothing else that could be.
    //
    // If this test ever needs the window to pass, the arbitration is broken and the window is
    // hiding it. That is the whole reason for keeping a zero-window case after the default became
    // non-zero.
    //
    // 800 ms is included precisely because the old 500 ms window could not cover it. It passes.
    for (gap in listOf(0L, 100L, 250L, 400L, 500L, 800L)) {
      runTest {
        val h = Harness(TestScope(testScheduler), settleWindowMillis = 0L)
        val a = h.submit("A")
        // A is in flight and stays in flight — resolution is NOT completed here.
        testScheduler.advanceTimeBy(gap)

        val b = h.submit("B")
        // Now let the older one come back. It must not act, at any spacing.
        h.resolveNow("A")
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
  fun `the shipped default coalesces a burst at every measured spacing`() {
    // The same spacings against the REAL default, so the shipped configuration is covered and not
    // merely the stripped-down one above.
    for (gap in listOf(0L, 100L, 250L, 500L, 800L, 999L)) {
      runTest {
        val h = Harness(TestScope(testScheduler))
        val a = h.submit("A")
        // A resolves IMMEDIATELY here — the hard case. Without a window A would launch at once and
        // no later token could undo it; only the quiet deadline can hold it back.
        h.resolveNow("A")
        testScheduler.advanceTimeBy(gap)

        val b = h.submit("B")
        h.resolveNow("B")
        testScheduler.advanceUntilIdle()

        assertEquals("gap=$gap: exactly one launch", listOf("B"), h.launches)
        assertEquals("gap=$gap: and it is the NEWEST", "B", h.launches.single())
        assertEquals("gap=$gap: A must be superseded, never failed", "superseded", a.outcome)
        assertEquals("gap=$gap: B must resolve", "resolved", b.outcome)
      }
    }
  }

  @Test
  fun `beyond the settle window two taps are two deliberate actions`() = runTest {
    // The counterpart, and why the window is a burst-coalescer rather than a mute button. Once the
    // quiet deadline has passed, A has launched and switched apps; a later tap is a NEW intention
    // and suppressing it would be a bug, not a fix.
    val h = Harness(TestScope(testScheduler))
    val a = h.submit("A")
    h.resolveNow("A")
    testScheduler.advanceUntilIdle() // well past the deadline: A completes and acts

    assertEquals("A acted alone", listOf("A"), h.launches)

    val b = h.submit("B")
    h.resolveNow("B")
    testScheduler.advanceUntilIdle()

    assertEquals("both act, deliberately", listOf("A", "B"), h.launches)
    assertEquals("and the newer link is last, so it is what plays", "B", h.launches.last())
    assertEquals("resolved", a.outcome)
    assertEquals("resolved", b.outcome)
  }

  @Test
  fun `the settle boundary belongs to the OLD burst, so equality coalesces`() {
    // The reviewer's instruction was to pick a side of the boundary and test the documented choice
    // rather than assert race-prone semantics. This documents the MEASURED behaviour rather than a
    // decreed one — the first draft of this test predicted the opposite and was wrong:
    //
    //   gap  999 ms -> coalesced. Deadline not reached.
    //   gap 1000 ms -> coalesced. A's delay(1000) fires at exactly 1000, but its completion has not
    //                  been delivered to the owner by the time B is submitted at the same instant,
    //                  so B still supersedes it.
    //   gap 1001 ms -> two actions. The first spacing that genuinely separates.
    //
    // So the window is [0, 1000] — INCLUSIVE at the top. Equality belongs to the old burst. If you
    // are changing the constant, this test tells you the coalesced range is `<= window`, not
    // `< window`.
    for ((gap, expected) in listOf(999L to listOf("B"), 1_000L to listOf("B"), 1_001L to listOf("A", "B"))) {
      runTest {
        val h = Harness(TestScope(testScheduler))
        h.submit("A")
        h.resolveNow("A")
        testScheduler.advanceTimeBy(gap)
        h.submit("B")
        h.resolveNow("B")
        testScheduler.advanceUntilIdle()
        assertEquals("gap=$gap", expected, h.launches)
      }
    }
  }

  @Test
  fun `the quiet timer restarts on every submission, even an instant one`() {
    // The nuance that a generation check alone would miss. Three taps at 600 ms, each resolving
    // INSTANTLY. If the deadline were anchored to the first submission it would expire at 1,000 ms
    // and the third tap at 1,200 ms would act separately. It is anchored per submission, so the
    // window keeps sliding and the burst stays one action.
    runTest {
      val h = Harness(TestScope(testScheduler))
      h.submit("A")
      h.resolveNow("A")
      testScheduler.advanceTimeBy(600)
      h.submit("B")
      h.resolveNow("B")
      testScheduler.advanceTimeBy(600) // 1,200 ms after A — past A's deadline, inside B's
      h.submit("C")
      h.resolveNow("C")
      testScheduler.advanceUntilIdle()

      assertEquals("a sliding window keeps the whole burst as one action", listOf("C"), h.launches)
    }
  }

  @Test
  fun `the older resolver returning FIRST still cannot act`() {
    // The ordering that lost on hardware: A overlaps B, but A's network call comes back first.
    // Arbitration must be by submission generation, never by completion order.
    for (gap in listOf(0L, 250L, 800L)) {
      runTest {
        val h = Harness(TestScope(testScheduler))
        val a = h.submit("A")
        testScheduler.advanceTimeBy(gap)
        val b = h.submit("B")

        h.resolveNow("A")
        testScheduler.advanceUntilIdle() // let A's completion run to exhaustion, alone
        assertEquals("gap=$gap: A must not have launched", emptyList<String>(), h.launches)

        h.resolveNow("B")
        testScheduler.advanceUntilIdle()

        assertEquals("gap=$gap: still exactly one launch", listOf("B"), h.launches)
        assertEquals("gap=$gap: superseded", "superseded", a.outcome)
        assertEquals("gap=$gap: B acted", "B", b.launched)
      }
    }
  }

  @Test
  fun `a window coalesces a future arrival, which arbitration alone cannot`() {
    // The one case a window uniquely fixes, isolated: A completes at 0 ms, B arrives at 800 ms.
    // Arbitration is powerless here because A's side effect would already have happened before B
    // existed. This is the test that justifies the window's existence at all.
    runTest {
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
    // ...and with the window off, the same sequence produces the defect. Without this the test
    // above would not show that the window is what did the work.
    runTest {
      val h = Harness(TestScope(testScheduler), settleWindowMillis = 0L)
      h.submit("A")
      h.resolveNow("A")
      testScheduler.advanceTimeBy(800)

      h.submit("B")
      h.resolveNow("B")
      testScheduler.advanceUntilIdle()

      assertEquals("without a window this is the two-launch defect", listOf("A", "B"), h.launches)
    }
  }

  // --- 2. a resolver that ignores cancellation and returns late ---------------------------------

  @Test
  fun `a resolver that ignores cancellation still cannot act`() = runTest {
    val launches = mutableListOf<String>()
    val returnedLate = mutableListOf<String>()
    var resumeA: Continuation<String>? = null
    val slowGate = CompletableDeferred<String>()

    val coordinator =
      LatestLinkCoordinator<String>(
        scope = TestScope(testScheduler),
        resolve = { input ->
          if (input == "A") {
            // `suspendCoroutine`, deliberately NOT `suspendCancellableCoroutine` and NOT
            // `withContext(NonCancellable)`. Cancelling the job cannot interrupt this, and nothing
            // between the resume and the return is a suspension point, so A GENUINELY RETURNS a
            // value after B has superseded it.
            //
            // The earlier NonCancellable version could not fail: on exiting the block back into an
            // already-cancelled parent, withContext's prompt cancellation threw before `resolve`
            // ever returned, so A never returned late and the guard under test never ran. The
            // `returnedLate` assertion below is what stops that regressing silently.
            val value = suspendCoroutine<String> { resumeA = it }
            returnedLate += value
            value
          } else {
            slowGate.await()
          }
        },
      )

    val ticketA = coordinator.submit("A")
    val outcomeA = mutableListOf<String>()
    val a = launch {
      when (ticketA.await()) {
        is LinkRequestOutcome.Superseded -> outcomeA += "superseded"
        is LinkRequestOutcome.Resolved -> {
          outcomeA += "resolved"
          coordinator.consumeIfCurrent(ticketA) { launches += "A" }
        }
        is LinkRequestOutcome.Failed -> outcomeA += "failed"
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

    // Only NOW does the uncancellable A come back — long after B superseded and acted.
    resumeA!!.resume("A")
    testScheduler.advanceUntilIdle()
    a.join()
    b.join()

    assertEquals("the test is worthless unless A really did return late", listOf("A"), returnedLate)
    assertEquals("a late A must not launch", listOf("B"), launches)
    assertEquals("and its owner must see Superseded, not a late Resolved", listOf("superseded"), outcomeA)
  }

  @Test
  fun `a resolver that ignores cancellation and then THROWS late is still only Superseded`() =
    runTest {
      val failures = mutableListOf<String>()
      val threwLate = mutableListOf<String>()
      var resumeA: Continuation<String>? = null
      val slowGate = CompletableDeferred<String>()

      val coordinator =
        LatestLinkCoordinator<String>(
          scope = TestScope(testScheduler),
          resolve = { input ->
            if (input == "A") {
              try {
                suspendCoroutine<String> { resumeA = it }
              } catch (e: IllegalStateException) {
                threwLate += "A"
                throw e
              }
            } else {
              slowGate.await()
            }
          },
        )

      val ticketA = coordinator.submit("A")
      val outcomeA = mutableListOf<String>()
      val a = launch {
        when (ticketA.await()) {
          is LinkRequestOutcome.Superseded -> outcomeA += "superseded"
          is LinkRequestOutcome.Resolved -> outcomeA += "resolved"
          is LinkRequestOutcome.Failed -> {
            outcomeA += "failed"
            failures += "A"
          }
        }
      }
      testScheduler.advanceTimeBy(100)

      coordinator.submit("B")
      slowGate.complete("B")
      testScheduler.advanceUntilIdle()

      resumeA!!.resumeWithException(IllegalStateException("late failure from a superseded request"))
      testScheduler.advanceUntilIdle()
      a.join()

      assertEquals("the test is worthless unless A really did throw late", listOf("A"), threwLate)
      assertEquals("a superseded request reports Superseded, never Failed", listOf("superseded"), outcomeA)
      assertEquals("and raises no user-facing failure", emptyList<String>(), failures)
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
  fun `a second tap inside the window suppresses the first`() = runTest {
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
  fun `the window is absorbed entirely when resolving takes longer`() = runTest {
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
