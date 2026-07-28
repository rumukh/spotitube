package com.example.spotitube.core

/**
 * Last-ditch protection against a link-handling cycle.
 *
 * Every launch this app performs targets an explicit package, so a cycle should be impossible by
 * construction. This guard exists because that guarantee rests on OEM behaviour we do not control
 * (chooser component exclusion, default-handler resolution). If the *same* link comes back to us
 * repeatedly in a short window, something is bouncing it, and continuing would spin forever.
 *
 * Pure Kotlin and clock-injected so the behaviour is unit-testable.
 */
class LoopGuard(
  private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
  private val maxHitsInWindow: Int = DEFAULT_MAX_HITS,
) {

  private val hits = LinkedHashMap<String, ArrayDeque<Long>>()

  /**
   * Records that [key] arrived at [nowMillis] and reports whether we are looping.
   *
   * Returns `true` once the same key has arrived [maxHitsInWindow] times inside [windowMillis].
   * A user re-tapping a link twice is normal and must not trip it.
   */
  @Synchronized
  fun recordAndCheck(key: String, nowMillis: Long): Boolean {
    val cutoff = nowMillis - windowMillis
    hits.values.forEach { queue -> while (queue.isNotEmpty() && queue.first() < cutoff) queue.removeFirst() }
    hits.entries.removeAll { it.value.isEmpty() }

    val queue = hits.getOrPut(key) { ArrayDeque() }
    queue.addLast(nowMillis)
    if (hits.size > MAX_TRACKED_KEYS) hits.remove(hits.keys.first())

    return queue.size >= maxHitsInWindow
  }

  @Synchronized
  fun reset() = hits.clear()

  companion object {
    const val DEFAULT_WINDOW_MILLIS = 10_000L
    const val DEFAULT_MAX_HITS = 3
    private const val MAX_TRACKED_KEYS = 16
  }
}
