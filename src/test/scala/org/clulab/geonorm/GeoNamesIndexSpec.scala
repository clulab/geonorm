package org.clulab.geonorm

import java.nio.file.{Files, Paths}

import org.apache.commons.io.FileUtils
import org.scalatest.{Matchers, WordSpec}

class GeoNamesIndexSpec extends WordSpec with Matchers {

  def mcIndexTests(index: GeoNamesIndex): Unit = {
    "find 1 exact match for Rocher de Monaco" in {
      assert(index.search("Rocher de Monaco").map(_._1.id) === Seq("2993456"))
    }
    "find 3 exact matches for Monaco" in {
      assert(index.search("Monaco").map(_._1.id).toSet === Set("2993457", "2993458", "3319178"))
    }
    "find a fuzzy match to Moneghetti from the misspelling Monegetii if fuzzy hits are enabled" in {
      index.search("Monegetii", maxFuzzyHits = 5, maxNGramHits = 0).map(_._1.id) should contain("3319177")
      index.search("Monegetii", maxFuzzyHits = 0, maxNGramHits = 0) shouldBe empty
    }
    "find an ngram match to Moneghetti from the misspelling Mghetti if ngram hits are enabled" in {
      index.search("Mghetti", maxFuzzyHits = 0, maxNGramHits = 5).map(_._1.id) should contain("3319177")
      index.search("Mghetti", maxFuzzyHits = 5, maxNGramHits = 0) shouldBe empty
      index.search("Mghetti", maxFuzzyHits = 0, maxNGramHits = 0) shouldBe empty
    }
    "find no matches for Zxxy" in {
      assert(index.search("Zxxy") === Seq.empty)
    }
    "produce no errors on Lucene keywords" in {
      index.search("OR")
      index.search("AND")
      index.search("NOT")
    }
    "produce no errors on inappropriately long strings with accents" in {
      // issue #3
      index.search("que llegan por vía aérea*.\nObjetivos. Se midieron los síntomas y la prevalencia de la gripe (también llamada influenza")
      // issue #4
      index.search("de los factores de riesgo del comportamiento asociados con la dinámica de la transmisión del virus. Al estar")
      // issue #5
      index.search("risultati hanno chiaramente identificato sia la necessità di coinvolgere gli adolescenti nella progettazione di interventi di")
    }
    "close without error" in {
      index.close()
    }
  }

  "an index" when {
    val indexTempDir = Files.createTempDirectory("geonames")
    try {
      "created from GeoNames MC.txt file" should {
        val mcTxt = Paths.get("src/test/resources/geonames/MC.txt")
        mcIndexTests(GeoNamesIndex.fromGeoNamesTxt(indexTempDir, mcTxt))
      }
      "loaded from a directory that indexed MC.txt" should {
        mcIndexTests(new GeoNamesIndex(indexTempDir))
      }
      "loaded from a directory that indexed MC.txt with maxFuzzyHits = 0 and maxNGramHits = 0" should {
        val index = new GeoNamesIndex(indexTempDir, maxFuzzyHits = 0, maxNGramHits = 0)
        "find no fuzzy or NGram matches to Moneghetti" in {
          index.search("Monegetii") shouldBe empty
          index.search("Mghetti") shouldBe empty
        }
        mcIndexTests(index)
      }
    } finally {
      FileUtils.deleteDirectory(indexTempDir.toFile)
    }
  }
}
