package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

  @Test
  fun `normalisation folds case punctuation and diacritics`() {
    assertEquals("beyonce", TextNormalizer.normalize("Beyoncé"))
    assertEquals("motorhead", TextNormalizer.normalize("Motörhead"))
    assertEquals("dont stop me now", TextNormalizer.normalize("Don't Stop Me Now"))
    assertEquals("spider man into the spider verse", TextNormalizer.normalize("Spider-Man: Into the Spider-Verse"))
    assertEquals("simon and garfunkel", TextNormalizer.normalize("Simon & Garfunkel"))
  }

  @Test
  fun `noise suffixes are stripped but real title suffixes are kept`() {
    assertEquals("never gonna give you up", TextNormalizer.canonical("Never Gonna Give You Up (2022 Remaster)"))
    assertEquals("never gonna give you up", TextNormalizer.canonical("Never Gonna Give You Up - Remastered 2022"))
    assertEquals("never gonna give you up", TextNormalizer.canonical("Never Gonna Give You Up (Official Video)"))
    assertEquals("never gonna give you up", TextNormalizer.canonical("Never Gonna Give You Up (feat. Someone)"))
    assertEquals("never gonna give you up", TextNormalizer.canonical("Never Gonna Give You Up - Radio Edit"))

    // The descriptive suffix here IS part of the song identity and must survive.
    assertEquals(
      "sunflower spider man into the spider verse",
      TextNormalizer.canonical("Sunflower - Spider-Man: Into the Spider-Verse"),
    )
  }

  @Test
  fun `the two real spellings of sunflower compare identical`() {
    assertEquals(
      TextNormalizer.canonical("Sunflower - Spider-Man: Into the Spider-Verse"),
      TextNormalizer.canonical("Sunflower (Spider-Man: Into the Spider-Verse)"),
    )
    assertEquals(
      1.0,
      TextNormalizer.similarity(
        "Sunflower - Spider-Man: Into the Spider-Verse",
        "Sunflower (Spider-Man: Into the Spider-Verse)",
      ),
      1e-9,
    )
  }

  @Test
  fun `similarity separates related from unrelated titles`() {
    assertEquals(1.0, TextNormalizer.similarity("Never Gonna Give You Up", "never gonna give you up"), 1e-9)
    assertTrue(TextNormalizer.similarity("Never Gonna Give You Up", "Together Forever") < 0.2)
    assertEquals(0.0, TextNormalizer.similarity("Never Gonna Give You Up", ""), 1e-9)
    // Extra descriptive word: high but not perfect.
    val partial = TextNormalizer.similarity("Never Gonna Give You Up", "Never Gonna Give You Up Pianoforte")
    assertTrue("was $partial", partial in 0.85..0.99)
  }

  @Test
  fun `clock durations are parsed and junk is rejected`() {
    assertEquals(214, TextNormalizer.parseClockDuration("3:34"))
    assertEquals(158, TextNormalizer.parseClockDuration("2:38"))
    assertEquals(3661, TextNormalizer.parseClockDuration("1:01:01"))
    assertEquals(245, TextNormalizer.parseClockDuration(" 4:05 "))
    for (junk in listOf(null, "", "2B plays", "Rick Astley", "3:5", "3", "1:2:3:4", "-1:00")) {
      assertNull("expected null for '$junk'", TextNormalizer.parseClockDuration(junk))
    }
  }
}
