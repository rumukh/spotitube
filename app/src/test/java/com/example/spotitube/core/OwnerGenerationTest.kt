package com.example.spotitube.core

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
 * The same-Activity ownership race, at the shape it actually occurs in.
 *
 * `LinkHandlerActivity` awaits every ticket in ONE `lifecycleScope`, so an owner that calls
 * `finish()` cancels the waiters of every request it owns — including a newer one. [SimulatedOwner]
 * below models exactly that: `close()` cancels the scope the waiters live in, the way onDestroy
 * cancels `lifecycleScope`.
 *
 * Without the [OwnerGeneration] guard the second link plays NOTHING: the older request's Superseded
 * callback closes the owner, and the newer request — which has resolved perfectly well — has no one
 * left to consume it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerGenerationTest {

  /** Models one Activity instance: a scope its waiters run in, cancelled when it closes. */
  private class SimulatedOwner(val label: String, parent: TestScope) {
    val owner = OwnerGeneration()
    val scopeJob = Job()
    val scope = TestScope(parent.testScheduler)
    var closed = false
    val waiters = mutableListOf<Job>()

    fun close() {
      closed = true
      waiters.forEach { it.cancel() } // onDestroy cancelling lifecycleScope
    }
  }

  @Test
  fun `one owner receiving A then B - A's superseded callback must not close the owner of B`() =
    runTest {
      val launches = mutableListOf<String>()
      val gates = mutableMapOf<String, CompletableDeferred<String>>()
      val coordinator =
        LatestLinkCoordinator<String>(
          scope = TestScope(testScheduler),
          resolve = { input -> gates.getOrPut(input!!) { CompletableDeferred() }.await() },
        )

      val activity = SimulatedOwner("single", this)

      fun handle(input: String) {
        val myGeneration = activity.owner.next()
        val ticket = coordinator.submit(input)
        activity.waiters +=
          launch {
            when (ticket.await()) {
              is LinkRequestOutcome.Superseded ->
                if (activity.owner.isCurrent(myGeneration)) activity.close()
              is LinkRequestOutcome.Resolved ->
                if (activity.owner.isCurrent(myGeneration) && !activity.closed) {
                  coordinator.consumeIfCurrent(ticket) { launches += input }
                  activity.close()
                }
              is LinkRequestOutcome.Failed -> Unit
            }
          }
      }

      handle("A")
      handle("B") // onNewIntent on the SAME instance
      gates.getOrPut("A") { CompletableDeferred() }.complete("A")
      gates.getOrPut("B") { CompletableDeferred() }.complete("B")
      testScheduler.advanceUntilIdle()

      assertEquals("B must be the one that acts", listOf("B"), launches)
      assertTrue("and the owner closes once B is done, not before", activity.closed)
    }

  @Test
  fun `without the guard the same sequence loses B entirely - the defect being fixed`() = runTest {
    val launches = mutableListOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<String>>()
    val coordinator =
      LatestLinkCoordinator<String>(
        scope = TestScope(testScheduler),
        resolve = { input -> gates.getOrPut(input!!) { CompletableDeferred() }.await() },
      )

    val activity = SimulatedOwner("unguarded", this)

    // Deliberately WITHOUT the owner-generation check, reproducing the shipped defect so the fix
    // above is measured against something rather than asserted against nothing.
    fun handleUnguarded(input: String) {
      val ticket = coordinator.submit(input)
      activity.waiters +=
        launch {
          when (ticket.await()) {
            is LinkRequestOutcome.Superseded -> activity.close()
            is LinkRequestOutcome.Resolved -> {
              coordinator.consumeIfCurrent(ticket) { launches += input }
              activity.close()
            }
            is LinkRequestOutcome.Failed -> Unit
          }
        }
    }

    handleUnguarded("A")
    handleUnguarded("B")
    gates.getOrPut("A") { CompletableDeferred() }.complete("A")
    gates.getOrPut("B") { CompletableDeferred() }.complete("B")
    testScheduler.advanceUntilIdle()

    assertEquals("the defect: tapping the same link twice plays NOTHING", emptyList<String>(), launches)
  }

  @Test
  fun `two owners - the older one closes and the newer one survives`() = runTest {
    val launches = mutableListOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<String>>()
    val coordinator =
      LatestLinkCoordinator<String>(
        scope = TestScope(testScheduler),
        resolve = { input -> gates.getOrPut(input!!) { CompletableDeferred() }.await() },
      )

    val first = SimulatedOwner("first", this)
    val second = SimulatedOwner("second", this)

    fun handle(activity: SimulatedOwner, input: String) {
      val myGeneration = activity.owner.next()
      val ticket = coordinator.submit(input)
      activity.waiters +=
        launch {
          when (ticket.await()) {
            is LinkRequestOutcome.Superseded ->
              if (activity.owner.isCurrent(myGeneration)) activity.close()
            is LinkRequestOutcome.Resolved ->
              if (activity.owner.isCurrent(myGeneration) && !activity.closed) {
                coordinator.consumeIfCurrent(ticket) { launches += input }
                activity.close()
              }
            is LinkRequestOutcome.Failed -> Unit
          }
        }
    }

    handle(first, "A")
    handle(second, "B") // a DIFFERENT link creates a second Activity instance
    gates.getOrPut("A") { CompletableDeferred() }.complete("A")
    gates.getOrPut("B") { CompletableDeferred() }.complete("B")
    testScheduler.advanceUntilIdle()

    assertEquals("only the newer link acts", listOf("B"), launches)
    assertTrue("the superseded instance must still close, not linger on screen", first.closed)
    assertTrue("and the winner closes after acting", second.closed)
  }

  @Test
  fun `a generation is current until a newer one is claimed`() {
    val owner = OwnerGeneration()
    val a = owner.next()
    assertTrue(owner.isCurrent(a))

    val b = owner.next()
    assertFalse("A is no longer current once B claims the owner", owner.isCurrent(a))
    assertTrue(owner.isCurrent(b))
  }
}
