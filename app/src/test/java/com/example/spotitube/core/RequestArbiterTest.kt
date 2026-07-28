package com.example.spotitube.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Race arbitration, forced deterministically.
 *
 * The bug these cover was found on a phone but is not reproducible on demand there: at ~250 ms
 * spacing the winner was whichever network call finished last, and at ~800 ms the *older* link won
 * three times out of three. A device can only demonstrate whichever race happens to occur; gating
 * each resolve on a [CompletableDeferred] lets a test choose the interleaving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestArbiterTest {

  /** Records every terminal callback so a test can assert nothing extra fired. */
  private class Recorder {
    val results = mutableListOf<String>()
    val superseded = mutableListOf<String>()
    val failures = mutableListOf<String>()

    fun submitTo(arbiter: RequestArbiter<String>, label: String, work: suspend () -> String) {
      arbiter.submit(
        resolve = work,
        onResult = { results += it },
        onSuperseded = { superseded += label },
        onFailure = { failures += "$label:${it.javaClass.simpleName}" },
      )
    }
  }

  @Test
  fun `a lone request wins`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    recorder.submitTo(arbiter, "A") { "A" }
    testScheduler.advanceUntilIdle()

    assertEquals(listOf("A"), recorder.results)
    assertEquals(emptyList<String>(), recorder.superseded)
  }

  @Test
  fun `two overlapping requests produce exactly one result, and it is the newer`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val gateA = CompletableDeferred<String>()
    val gateB = CompletableDeferred<String>()

    recorder.submitTo(arbiter, "A") { gateA.await() }
    recorder.submitTo(arbiter, "B") { gateB.await() }

    // Let the OLDER one finish first — the exact interleaving that made the older link win on
    // device. It must still lose.
    gateA.complete("A")
    gateB.complete("B")
    testScheduler.advanceUntilIdle()

    assertEquals("exactly one launch, and it is B", listOf("B"), recorder.results)
    assertEquals(emptyList<String>(), recorder.failures)
  }

  @Test
  fun `the newer request still wins when it finishes first`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val gateA = CompletableDeferred<String>()
    val gateB = CompletableDeferred<String>()

    recorder.submitTo(arbiter, "A") { gateA.await() }
    recorder.submitTo(arbiter, "B") { gateB.await() }

    gateB.complete("B")
    gateA.complete("A")
    testScheduler.advanceUntilIdle()

    assertEquals(listOf("B"), recorder.results)
  }

  @Test
  fun `a superseded request reports superseded and never a result`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val gateA = CompletableDeferred<String>()

    recorder.submitTo(arbiter, "A") { gateA.await() }
    recorder.submitTo(arbiter, "B") { "B" }
    testScheduler.advanceUntilIdle()

    assertEquals(listOf("B"), recorder.results)
    assertEquals("A must be told it lost, exactly once", listOf("A"), recorder.superseded)
    assertEquals("being superseded is not a failure", emptyList<String>(), recorder.failures)
  }

  @Test
  fun `cancellation never becomes a user-facing failure`() = runTest {
    // The defect this closes: runCatching swallows CancellationException, so a superseded resolve
    // reported itself as Unsupported and could toast "Job was cancelled" at the user.
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val neverCompletes = CompletableDeferred<String>()

    recorder.submitTo(arbiter, "A") { neverCompletes.await() }
    recorder.submitTo(arbiter, "B") { "B" }
    testScheduler.advanceUntilIdle()

    assertEquals(emptyList<String>(), recorder.failures)
    assertEquals(listOf("A"), recorder.superseded)
    assertEquals(listOf("B"), recorder.results)
  }

  @Test
  fun `a real failure is reported only when it is still newest`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()

    recorder.submitTo(arbiter, "A") { throw java.io.IOException("boom") }
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("A:IOException"), recorder.failures)
    assertEquals(emptyList<String>(), recorder.results)
  }

  @Test
  fun `a superseded request that fails reports superseded, not the failure`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val gateA = CompletableDeferred<String>()

    recorder.submitTo(arbiter, "A") {
      gateA.await()
      throw java.io.IOException("boom")
    }
    recorder.submitTo(arbiter, "B") { "B" }
    testScheduler.advanceUntilIdle()
    gateA.complete("unused")
    testScheduler.advanceUntilIdle()

    assertEquals("a loser's error is not the user's problem", emptyList<String>(), recorder.failures)
    assertEquals(listOf("B"), recorder.results)
  }

  @Test
  fun `a burst of five produces exactly one result, the last`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val gates = (1..5).map { CompletableDeferred<String>() }

    gates.forEachIndexed { i, gate -> recorder.submitTo(arbiter, "R$i") { gate.await() } }
    // Complete in a deliberately unhelpful order.
    listOf(2, 0, 4, 1, 3).forEach { gates[it].complete("R$it") }
    testScheduler.advanceUntilIdle()

    assertEquals(listOf("R4"), recorder.results)
    assertEquals("every loser is accounted for", 4, recorder.superseded.size)
    assertEquals(emptyList<String>(), recorder.failures)
  }

  @Test
  fun `every submission ends in exactly one terminal callback`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()
    val gates = (1..4).map { CompletableDeferred<String>() }

    gates.forEachIndexed { i, gate -> recorder.submitTo(arbiter, "R$i") { gate.await() } }
    gates.forEach { it.complete("x") }
    testScheduler.advanceUntilIdle()

    val total = recorder.results.size + recorder.superseded.size + recorder.failures.size
    assertEquals("4 submissions must produce 4 terminal callbacks", 4, total)
  }

  @Test
  fun `sequential requests each win in turn`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val recorder = Recorder()

    recorder.submitTo(arbiter, "A") { "A" }
    testScheduler.advanceUntilIdle()
    recorder.submitTo(arbiter, "B") { "B" }
    testScheduler.advanceUntilIdle()

    assertEquals(listOf("A", "B"), recorder.results)
    assertEquals("a completed request is not retroactively superseded", emptyList<String>(), recorder.superseded)
  }

  @Test
  fun `tickets increase monotonically`() = runTest {
    val arbiter = RequestArbiter<String>(TestScope(testScheduler))
    val first = arbiter.submit({ "a" }, {}, {}, {})
    val second = arbiter.submit({ "b" }, {}, {}, {})
    testScheduler.advanceUntilIdle()
    assertTrue(second > first)
    assertEquals(second, arbiter.newest)
  }
}
