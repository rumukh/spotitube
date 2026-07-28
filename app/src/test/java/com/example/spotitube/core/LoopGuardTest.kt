package com.example.spotitube.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {

  @Test
  fun `a single tap never trips the guard`() {
    val guard = LoopGuard()
    assertFalse(guard.recordAndCheck("https://open.spotify.com/track/abc", 0L))
  }

  @Test
  fun `a user re-tapping the same link twice is not a loop`() {
    val guard = LoopGuard()
    assertFalse(guard.recordAndCheck("url", 0L))
    assertFalse(guard.recordAndCheck("url", 1_500L))
  }

  @Test
  fun `the same link three times inside the window is a loop`() {
    val guard = LoopGuard()
    assertFalse(guard.recordAndCheck("url", 0L))
    assertFalse(guard.recordAndCheck("url", 200L))
    assertTrue(guard.recordAndCheck("url", 400L))
  }

  @Test
  fun `hits outside the window are forgotten`() {
    val guard = LoopGuard(windowMillis = 10_000L)
    assertFalse(guard.recordAndCheck("url", 0L))
    assertFalse(guard.recordAndCheck("url", 5_000L))
    // The first two have aged out by now, so this is a fresh first hit.
    assertFalse(guard.recordAndCheck("url", 40_000L))
    assertFalse(guard.recordAndCheck("url", 41_000L))
    assertTrue(guard.recordAndCheck("url", 42_000L))
  }

  @Test
  fun `different links do not accumulate against each other`() {
    val guard = LoopGuard()
    assertFalse(guard.recordAndCheck("a", 0L))
    assertFalse(guard.recordAndCheck("b", 10L))
    assertFalse(guard.recordAndCheck("c", 20L))
    assertFalse(guard.recordAndCheck("a", 30L))
    assertFalse(guard.recordAndCheck("b", 40L))
    assertTrue(guard.recordAndCheck("a", 50L))
  }

  @Test
  fun `reset clears history`() {
    val guard = LoopGuard()
    guard.recordAndCheck("url", 0L)
    guard.recordAndCheck("url", 10L)
    guard.reset()
    assertFalse(guard.recordAndCheck("url", 20L))
  }

  @Test
  fun `tracking many distinct links does not grow without bound`() {
    val guard = LoopGuard()
    repeat(500) { i -> assertFalse(guard.recordAndCheck("url-$i", i.toLong())) }
    // Still able to detect a loop after all that churn.
    assertFalse(guard.recordAndCheck("hot", 1_000L))
    assertFalse(guard.recordAndCheck("hot", 1_100L))
    assertTrue(guard.recordAndCheck("hot", 1_200L))
  }
}
