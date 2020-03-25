package org.clulab.geonorm

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import org.apache.lucene.analysis.{Analyzer, LowerCaseFilter}
import org.apache.lucene.analysis.core.{KeywordAnalyzer, KeywordTokenizer}
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.ngram.NGramTokenFilter
import org.apache.lucene.analysis.pattern.PatternReplaceFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.document.{Document, Field, StoredField, StringField, TextField}
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig, Term}
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.{IndexSearcher, Query, TermQuery}
import org.apache.lucene.search.grouping.GroupingSearch
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.automaton.TooComplexToDeterminizeException


class GeoNamesEntry(document: Document) {
  lazy val id: String = document.get("id")
  lazy val name: String = document.get("canonical-name")
  lazy val featureCode: String = document.get("feature-code")
  lazy val population: Long = document.get("population").toLong
}


object GeoNamesIndexConfig {
  val idField: String => Field = new StoredField("id", _)
  val canonicalNameField: String => Field = new StoredField("canonical-name", _)
  val nameField: String => Field = new TextField("name", _, Field.Store.NO)
  val ngramsField: String => Field = new TextField("ngrams", _, Field.Store.NO)
  val latitudeField: Float => Field = new StoredField("latitude", _)
  val longitudeField: Float => Field = new StoredField("longitude", _)
  val featureCodeField: String => Field = new StoredField("feature-code", _)
  val populationField: Long => Field = new StoredField("population", _)
  val idEndField: Field = new StringField("idEnd", "x", Field.Store.NO)

  val idEndQuery: Query = new TermQuery(new Term("idEnd", "x"))
  val nameAnalyzer: Analyzer = new Analyzer {
    override def createComponents(fieldName: String): Analyzer.TokenStreamComponents = {
      val tokenizer = new KeywordTokenizer
      val filter = new PatternReplaceFilter(new LowerCaseFilter(tokenizer), Pattern.compile("\\W+"), "", true)
      new Analyzer.TokenStreamComponents(tokenizer, filter)
    }
  }
  val ngramAnalyzer: Analyzer = new Analyzer {
    override def createComponents(fieldName: String): Analyzer.TokenStreamComponents = {
      val tokenizer = new StandardTokenizer
      val filter = new NGramTokenFilter(new LowerCaseFilter(tokenizer), 3, 3)
      new Analyzer.TokenStreamComponents(tokenizer, filter)
    }
  }
  val analyzer: Analyzer = new PerFieldAnalyzerWrapper(
    new KeywordAnalyzer, Map("name" -> nameAnalyzer, "ngrams" -> ngramAnalyzer).asJava)
}


object GeoNamesIndex {
  def main(args: Array[String]): Unit = args match {
    case Array("index", indexPath, geoNamesPath) =>
      GeoNamesIndex.fromGeoNamesTxt(Paths.get(indexPath), Paths.get(geoNamesPath))
    case Array("search", indexPath, queryStrings @ _*) =>
      val index = new GeoNamesIndex(Paths.get(indexPath))
      for (queryString <- queryStrings) {
        println(queryString)
        for ((entry, score) <- index.search(queryString, 20)) {
          println(f"$score%.3f ${entry.id} ${entry.name} ${entry.featureCode} ${entry.population}")
        }
      }
      index.close()
  }

  def fromGeoNamesTxt(indexPath: Path, geoNamesPath: Path): GeoNamesIndex = {
    // create an index writer
    val dir = FSDirectory.open(indexPath)
    val config = new IndexWriterConfig(GeoNamesIndexConfig.analyzer)
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE)
    val writer = new IndexWriter(dir, config)

    // walk through each line of the GeoNames file
    for (line <- Files.lines(geoNamesPath).iterator.asScala) {
      val Array(geoNameID, canonicalName, asciiName, alternateNames, latitude, longitude,
      _, featureCode, _, _, _, _, _, _, population, _, _, _, _) = line.split("\t")

      // generate a document for each name of this ID
      val docs = for (name <- Array(canonicalName, asciiName) ++ alternateNames.split(",")) yield {
        val doc = new Document
        doc.add(GeoNamesIndexConfig.idField(geoNameID))
        doc.add(GeoNamesIndexConfig.canonicalNameField(canonicalName))
        doc.add(GeoNamesIndexConfig.nameField(name))
        doc.add(GeoNamesIndexConfig.ngramsField(name))
        doc.add(GeoNamesIndexConfig.latitudeField(latitude.toFloat))
        doc.add(GeoNamesIndexConfig.longitudeField(longitude.toFloat))
        doc.add(GeoNamesIndexConfig.featureCodeField(featureCode))
        doc.add(GeoNamesIndexConfig.populationField(population.toLong))
        doc
      }

      // mark the last document (name) for the ID
      docs.last.add(GeoNamesIndexConfig.idEndField)

      // write all documents (names) for this ID in a block
      val docsList: java.util.List[Document] = java.util.Arrays.asList(docs: _*)
      writer.addDocuments(docsList)
    }
    writer.close()

    new GeoNamesIndex(indexPath)
  }
}

class GeoNamesIndex(indexPath: Path) {
  private val reader = DirectoryReader.open(FSDirectory.open(indexPath))
  private val searcher = new IndexSearcher(reader)
  private val groupingSearch = new GroupingSearch(GeoNamesIndexConfig.idEndQuery)

  def search(queryString: String, maxFuzzyHits: Int = 5): Array[(GeoNamesEntry, Float)] = {
    // create these locally, since they are not thread-safe
    val nameQueryParser = new QueryParser("name", GeoNamesIndexConfig.analyzer)
    val ngramsQueryParser = new QueryParser("ngrams", GeoNamesIndexConfig.analyzer)

    // escape special characters for queries to "name" field
    val luceneSpecialCharacters = """([-+&|!(){}\[\]^"~*?:\\/]|\bAND\b|\bOR\b|\bNOT\b)"""
    val escapedQueryString = queryString.replaceAll(luceneSpecialCharacters, """\\$1""")
    val whitespaceEscapedQueryString = escapedQueryString.replaceAll("""\s""", """\\ """)

    // first look for an exact match of the input phrase (the "name" field ignores spaces, punctuation, etc.)
    var results = scoredEntries(nameQueryParser.parse(whitespaceEscapedQueryString), 1000)

    // if there's no exact match, search for fuzzy (1-2 edit-distance) matches
    if (results.isEmpty) {
      try {
        results = scoredEntries(nameQueryParser.parse(whitespaceEscapedQueryString + "~"), maxFuzzyHits)
      } catch {
        case _: TooComplexToDeterminizeException =>
          // continue to n-gram search if the query is too complex for fuzzy search
      }
    }
    // if there's no fuzzy match, search for n-gram matches
    if (results.isEmpty) {
      results = scoredEntries(ngramsQueryParser.parse(escapedQueryString), maxFuzzyHits)
    }
    // sort first by retrieval score, then by population, then by feature code (e.g., ADM1 before ADM3 and PPL)
    results.sortBy{
      case (entry, score) => (-score, -math.log10(entry.population + 1).round, entry.featureCode)
    }
  }

  def scoredEntries(query: Query, maxHits: Int): Array[(GeoNamesEntry, Float)] = {
    // perform a group-based search, where each group represents all names for a GeoNames ID
    val topGroups = groupingSearch.search[String](searcher, query, 0, maxHits)

    // for each of the hits, return an object representing the GeoNames entry, and the retrieval score
    if (topGroups == null) {
      Array.empty
    } else {
      for (group <- topGroups.groups) yield {
        val headDoc = searcher.doc(group.scoreDocs.head.doc)
        (new GeoNamesEntry(headDoc), group.maxScore)
      }
    }
  }

  def close(): Unit = {
    reader.close()
  }
}
