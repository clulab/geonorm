package org.clulab.geonorm

import java.nio.file.{Files, Paths}
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

class GeoNormSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  val indexTempDir = Files.createTempDirectory("geonames")
  var geoNamesIndexOpt: Option[GeoNamesIndex] = None

  override def afterAll(): Unit = {
    // The index should be closed after all tests have completed.
    geoNamesIndexOpt.map(_.close())
    // This directory can be deleted only after all indexes have been closed.
    FileUtils.deleteDirectory(indexTempDir.toFile)
  }

  "an extractor" when {
    "loaded from the default location" should {
      val extractor = new GeoLocationExtractor()
      "detect a single location" in {
        val words = "I live in New York .".split(" ")
        assert(extractor(Array(words)) === Array(Array((3, 5))))
      }
      "detect multiple locations" in {
        val words = "The capital of North Dakota is Bismarck .".split(" ")
        assert(extractor(Array(words)) === Array(Array((3, 5), (6, 7))))
      }
      "handle batches" in {
        val sentences = Array(
          "She was born in South Sudan .",
          "His friend from Ethiopia is funny ."
        ).map(_.split(" "))
        assert(extractor(sentences) === Array(Array((4, 6)), Array((3, 4))))
      }
    }
  }

  "a normalizer" when {
    "loaded from MC.txt with the default linear model" should {
      val mcTxt = Paths.get("src/test/resources/geonames/MC.txt")
      geoNamesIndexOpt = Some(GeoNamesIndex.fromGeoNamesTxt(indexTempDir, mcTxt))
      val index = geoNamesIndexOpt.get
      val normalizer = new GeoLocationNormalizer(index)
      "select the correct exact-match Monaco" in {
        assert(normalizer("Monaco").head._1.id === "2993457")
      }
      "rank Monte-Carlo first when searching for Carlo" in {
        assert(normalizer("Carlo").head._1.id === "2992741")
      }
      "fail to find any match for Zxxy" in {
        assert(normalizer("Zxxy") === Array.empty)
      }
    }
  }
}
