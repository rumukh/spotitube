package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
  fun `non latin scripts survive normalisation`() {
    // The old ASCII-only separator class reduced every one of these to the empty string, which
    // made non-Latin tracks impossible to match at all.
    val cases =
      mapOf(
        "Кино" to "кино",
        "Гру́ппа крови" to "группа крови",
        "防弾少年団" to "防弾少年団",
        "夜に駆ける" to "夜に駆ける",
        "밤편지" to "밤편지",
        "Σ' αγαπώ" to "σ αγαπω",
      )
    for ((input, expected) in cases) {
      val actual = TextNormalizer.normalize(input)
      assertTrue("'$input' normalised to empty", actual.isNotEmpty())
      assertEquals(input, expected, actual)
    }
  }

  @Test
  fun `non latin titles and artists still compare`() {
    assertEquals(1.0, TextNormalizer.similarity("夜に駆ける", "夜に駆ける"), 1e-9)
    assertEquals(1.0, TextNormalizer.similarity("Кино - Группа крови", "Кино — Группа крови"), 1e-9)
    assertTrue(TextNormalizer.similarity("夜に駆ける", "群青") < 0.2)
    // A bracketed noise suffix on the YouTube side must not sink a CJK title.
    assertTrue(TextNormalizer.similarity("夜に駆ける", "夜に駆ける (Official Video)") > 0.9)
  }

  @Test
  fun `full width and compatibility forms fold together`() {
    assertEquals(TextNormalizer.normalize("ＹＯＡＳＯＢＩ"), TextNormalizer.normalize("YOASOBI"))
    assertEquals(TextNormalizer.normalize("ﬁnale"), TextNormalizer.normalize("finale"))
  }

  // --- the pair-conditioned romanisation rule ----------------------------------------------------

  @Test
  fun `a romanisation suffix on one side compares equal`() {
    val cases =
      mapOf(
        "夜の踊り子" to "夜の踊り子 - Yoru No Odoriko",
        "高嶺の花子さん" to "高嶺の花子さん - Takaneno Hanakosan",
        "ブルーアンバー" to "ブルーアンバー - Blue Amber",
        "青と夏" to "青と夏 - Ao To Natsu",
        "밤편지" to "밤편지 - Through the Night",
        "Кино" to "Кино - Gruppa Krovi",
        // Macrons are Latin script, so a Hepburn transliteration still qualifies.
        "東京" to "東京 - Tōkyō",
      )
    for ((plain, romanised) in cases) {
      assertEquals(plain, 1.0, TextNormalizer.similarity(plain, romanised), 1e-9)
      assertEquals("$plain (reversed)", 1.0, TextNormalizer.similarity(romanised, plain), 1e-9)
    }
  }

  @Test
  fun `titles differing only by a latin suffix stay distinct`() {
    // The case that killed the context-free version: stripping in canonical() collapsed these into
    // one title, and artist, album and duration are identical for adjacent tracks so nothing else
    // would have caught it.
    val one = "同じ頭 - Part One"
    val two = "同じ頭 - Part Two"
    assertTrue("must not collapse to equal", TextNormalizer.similarity(one, two) < 1.0)
    assertNotEquals(TextNormalizer.canonical(one), TextNormalizer.canonical(two))
  }

  @Test
  fun `a numbered suffix is never treated as a romanisation`() {
    // Digits are excluded deliberately: "Part 1" is identity, not transliteration.
    assertTrue(TextNormalizer.similarity("同じ頭", "同じ頭 - Part 1") < 1.0)
    assertTrue(TextNormalizer.similarity("同じ頭 - Part 1", "同じ頭 - Part 2") < 1.0)
  }

  @Test
  fun `a latin head never qualifies, so the false-positive class stays removed`() {
    // "Circles" vs "Circles Around The Sun" is exactly the class we spent the evening removing.
    assertTrue(TextNormalizer.similarity("Circles", "Circles - Around The Sun") < 1.0)
    assertTrue(TextNormalizer.similarity("Circles", "Circles Around The Sun") < 1.0)
    assertTrue(
      TextNormalizer.similarity("Sunflower", "Sunflower - Spider-Man: Into the Spider-Verse") < 1.0,
    )
  }

  @Test
  fun `a different non latin song does not match through its romanisation`() {
    assertEquals(0.0, TextNormalizer.similarity("夜の踊り子", "新宝島 - Shin Takara Jima"), 1e-9)
    assertEquals(0.0, TextNormalizer.similarity("夜の踊り子", "怪獣 - Kaiju"), 1e-9)
  }

  @Test
  fun `a non latin suffix is not a romanisation`() {
    assertTrue(TextNormalizer.similarity("夜の踊り子", "夜の踊り子 - 踊り子") < 1.0)
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
