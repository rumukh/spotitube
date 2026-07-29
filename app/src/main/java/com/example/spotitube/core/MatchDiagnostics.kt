package com.example.spotitube.core

import java.util.Locale

/**
 * Everything the app is allowed to say about a match decision on a log line.
 *
 * The type exists so the privacy rule is *structural* rather than a habit that has to be
 * remembered at every call site. It holds numbers, the threshold those numbers are judged against,
 * and the fixed veto/note category names that [MatchScorer] itself owns — and there is no field a
 * title, artist, album or search query could be put in. The candidate's opaque `videoId` is added
 * by the caller, because the two paths label it differently: the video we launched, versus the one
 * that lost.
 *
 * [core] and [rank] are kept apart deliberately, and that separation is the whole point of this
 * type. [core] is the evidence score and the only quantity [threshold] is ever applied to; [rank]
 * is the ordering score and decides *which* candidate won, never *whether* it was good enough.
 * A single field named `score` used to carry [rank] on the PLAY path while SEARCH reason text used
 * the same word for [core], so a device regression baseline comparing "the score" of a play against
 * "the score" of a search was comparing two different quantities under one name — and neither line
 * said which it was.
 */
data class MatchDiagnostics(
  /** Evidence score: title, artist and album only. The quantity [threshold] gates on. */
  val core: Double,
  /** Ordering score: [core] plus presentation bonuses. Never gates anything. */
  val rank: Double,
  val titleScore: Double,
  val artistScore: Double,
  val albumScore: Double,
  /** The bar [core] had to clear, carried so a reader need not know the constant. */
  val threshold: Double,
  /** Fixed category names owned by [MatchScorer], e.g. `artist`, `variant:karaoke`, `duration±14s`. */
  val vetoes: List<String>,
  /** Fixed category names owned by [MatchScorer], e.g. `album-match`, `artist-channel`. */
  val notes: List<String>,
) {

  /**
   * The log-safe rendering, and it is deliberately the *same* renderer on the PLAY and SEARCH
   * paths so the two outcomes disclose identical fields and can be compared line for line.
   *
   * Every quantity is named. There is no bare `score` label left for a reader — or a regression
   * baseline — to guess the meaning of.
   *
   * [Locale.ROOT], not the default locale: `%.3f` under a comma-decimal locale renders `1,000`,
   * which changes the field separator inside a comma-joined line and makes a device report depend
   * on the phone's language settings. The owner's phone is not the only phone this could run on.
   */
  fun format(): String =
    String.format(
      Locale.ROOT,
      "core=%.3f rank=%.3f (t=%.2f a=%.2f al=%.2f)%s threshold=%.2f%s",
      core,
      rank,
      titleScore,
      artistScore,
      albumScore,
      if (vetoes.isNotEmpty()) " VETO[${vetoes.joinToString("|")}]" else "",
      threshold,
      if (notes.isNotEmpty()) " notes=${notes.joinToString(",")}" else "",
    )
}
