package org.clulab.geonorm

import java.io.{BufferedReader, InputStream, InputStreamReader, Reader}
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import de.bwaldvogel.liblinear.{Feature, FeatureNode, Linear, Model, Parameter, Problem, SolverType}
import org.apache.commons.io.IOUtils
import org.tensorflow.{Graph, Session, Tensor}


object GeoLocationExtractor {
  val I_LOC = 0
  val B_LOC = 1
  val O_LOC = 2
  val PADDING_TOKEN = "PADDING_TOKEN"
  val UNKNOWN_TOKEN = "UNKNOWN_TOKEN"
  val TF_INPUT = "words_input"
  val TF_OUTPUT = "time_distributed_1/Reshape_1"


  def loadNetwork(inputStream: InputStream): Session = {
    val graph = new Graph
    graph.importGraphDef(IOUtils.toByteArray(inputStream))
    new Session(graph)
  }

  def loadVocabulary(inputStream: InputStream): Map[String, Int] = {
    val reader = new BufferedReader(new InputStreamReader(inputStream))
    val wordToIndex = reader.lines.iterator.asScala.map(_.split(' ')).map {
      case Array(word, n) => (word, n.toInt)
    }
    wordToIndex.toMap
  }
}

class GeoLocationExtractor(taggerModel: Session = GeoLocationExtractor.loadNetwork(getClass.getResourceAsStream("/org/clulab/geonorm/model/geonorm_model.pb")),
                           wordToIndex: Map[String, Int] = GeoLocationExtractor.loadVocabulary(getClass.getResourceAsStream("/org/clulab/geonorm/en/geoloc-extractor-vocabulary.txt"))) {

  lazy private val unknownInt = wordToIndex(GeoLocationExtractor.UNKNOWN_TOKEN)
  lazy private val paddingInt = wordToIndex(GeoLocationExtractor.PADDING_TOKEN)

  /**
    * Finds locations in a document.
    *
    * @param sentenceWords A series of sentences, where each sentence is an array of word Strings.
    * @return For each input sentence, a list of locations, where each location is a begin word,
    *         and end word (exclusive).
    */
  def apply(sentenceWords: Array[Array[String]]): Array[Array[(Int, Int)]] = if (sentenceWords.isEmpty) Array.empty else {
    // get the word-level features, and pad to the maximum sentence length
    val maxLength = sentenceWords.map(_.length).max
    val sentenceFeatures = for (words <- sentenceWords) yield {
      words.map(wordToIndex.getOrElse(_, this.unknownInt)).padTo(maxLength, this.paddingInt)
    }

    // feed the word features through the Tensorflow model
    import GeoLocationExtractor.{TF_INPUT, TF_OUTPUT}
    val input = Tensor.create(sentenceFeatures)
    val Array(output: Tensor[_]) = taggerModel.runner().feed(TF_INPUT, input).fetch(TF_OUTPUT).run().toArray

    // convert word-level probability distributions to word-level class predictions
    val Array(nSentences, nWords, nClasses) = output.shape
    val sentenceWordProbs = Array.ofDim[Float](nSentences.toInt, nWords.toInt, nClasses.toInt)
    output.copyTo(sentenceWordProbs)
    val sentenceWordPredictions: Array[Array[Int]] = sentenceWordProbs.map(_.map{ wordProbs =>
      val max = wordProbs.max
      wordProbs.indexWhere(_ == max)
    })

    // convert word-level class predictions into span-level geoname predictions
    import GeoLocationExtractor.{B_LOC, I_LOC, O_LOC}
    for ((words, paddedWordPredictions) <- sentenceWords zip sentenceWordPredictions) yield {
      // trim off any predictions on padding words
      val wordPredictions = paddedWordPredictions.take(words.length)
      for {
        (wordPrediction, wordIndex) <- wordPredictions.zipWithIndex

        // a start is either a B, or an I that is following an O
        if wordPrediction == B_LOC || (wordPrediction == I_LOC &&
          // an I at the beginning of a sentence is not considered to be a start,
          // since as of Jun 2019, such tags seemed to be mostly errors (non-locations)
          wordIndex != 0 && wordPredictions(wordIndex - 1) == O_LOC)
      } yield {

        // the end index is the first B or O (i.e., non-I) following the start
        val end = wordPredictions.indices.indexWhere(wordPredictions(_) != I_LOC, wordIndex + 1)
        val endIndex = if (end == -1) words.length else end

        // yield the token span
        (wordIndex, endIndex)
      }
    }
  }

}


object GeoLocationNormalizer {

  def loadModel(inputStream: InputStream): Model = {
    Model.load(new BufferedReader(new InputStreamReader(inputStream)))
  }

  def loadModel(path: Path): Model = {
    val reader = Files.newBufferedReader(path)
    try {
      Model.load(reader)
    } finally {
      reader.close()
    }
  }

  def train(geoNamesIndex: GeoNamesIndex,
            trainingData: Iterator[(String, Seq[(Int, Int)], Seq[String])]): GeoLocationNormalizer = {
    val reranker = new GeoLocationNormalizer(geoNamesIndex)

    // convert training data into features and labels
    val featureLabels: Iterator[(Array[Feature], Double)] = for {
      (text, spans, geoIDs) <- trainingData
      (span, geoID) <- spans zip geoIDs
      scoredEntries = reranker.scoredEntries(text, span)

      // pair each correct entry with all incorrect ones
      correctIndices = scoredEntries.indices.filter(i => scoredEntries(i)._1.id == geoID).toSet
      correctIndex <- correctIndices
      incorrectIndex <- scoredEntries.indices
      if !correctIndices.contains(incorrectIndex)

      // include the pair in both orders (correct first, and correct second)
      labeledFeatures <- Seq(
        (reranker.pairFeatures(scoredEntries(correctIndex), scoredEntries(incorrectIndex)), 1.0),
        (reranker.pairFeatures(scoredEntries(incorrectIndex), scoredEntries(correctIndex)), 0.0)
      )
    } yield {
      labeledFeatures
    }

    // feed the features and labels into liblinear
    val (features, labels) = featureLabels.toArray.unzip
    val problem = new Problem
    problem.x = features
    problem.y = labels
    problem.l = labels.length
    problem.n = 2 + featureCodePairIndex.size + 1
    problem.bias = 1

    // use a logistic regression model since we need probabilities
    val param = new Parameter(SolverType.L1R_LR, 1.0, 0.01)
    val model = Linear.train(problem, param)

    // return the trained reranking model
    new GeoLocationNormalizer(geoNamesIndex, Some(model))
  }

  // country, state, region, ... and city, village, ... from http://www.geonames.org/export/codes.html
  private val featureCodes = """
    ADM1 ADM1H ADM2 ADM2H ADM3 ADM3H ADM4 ADM4H ADM5 ADMD ADMDH LTER PCL PCLD PCLF PCLH PCLI PCLIX PCLS PRSH TERR ZN ZNB
    PPL PPLA PPLA2 PPLA3 PPLA4 PPLC PPLCH PPLF PPLG PPLH PPLL PPLQ PPLR PPLS PPLW PPLX STLMT
    """.split("""\s+""")

  private val featureCodeSet = featureCodes.toSet

  private def featureCode(entry: GeoNamesEntry): String = {
    if (featureCodeSet.contains(entry.featureCode)) entry.featureCode else "UNK"
  }

  private val featureCodePairIndex: Map[(String, String), Int] = {
    val pairs = for (x <- featureCodes ++ Array("UNK"); y <- featureCodes ++ Array("UNK")) yield (x, y)
    pairs.zipWithIndex.toMap
  }
}


class GeoLocationNormalizer(geoNamesIndex: GeoNamesIndex,
                            linearModel: Option[Model] = Some(GeoLocationNormalizer.loadModel(getClass.getResourceAsStream("/org/clulab/geonorm/geonames-reranker.model")))) {

  def scoredEntries(text: String, span: (Int, Int)): Array[(GeoNamesEntry, Float)] = span match {
    case (start, end) => geoNamesIndex.search(text.substring(start, end))
  }

  private def pairFeatures(entryScore1: (GeoNamesEntry, Float), entryScore2: (GeoNamesEntry, Float)): Array[Feature] = {
    val (entry1, score1) = entryScore1
    val (entry2, score2) = entryScore2
    val featureCodePair = (GeoLocationNormalizer.featureCode(entry1), GeoLocationNormalizer.featureCode(entry2))
    Array[Feature](
      // difference in retrieval scores
      new FeatureNode(1, score1 - score2),
      // difference in log-populations
      new FeatureNode(2, math.log10(entry1.population + 1) - math.log10(entry2.population + 1)),
      // the pair of feature types, e.g., (ADM1, ADM2)
      new FeatureNode(3 + GeoLocationNormalizer.featureCodePairIndex(featureCodePair), 1))
  }

  def apply(text: String): Array[(GeoNamesEntry, Float)] = apply(text, (0, text.length))

  def apply(text: String, span: (Int, Int)): Array[(GeoNamesEntry, Float)] = {
    linearModel match {
      // if there is no model, rely on the searcher's order
      case None => this.scoredEntries(text, span)
      // if there is a model, rerank the search results
      case Some(model) =>
        // determine which index liblinear is using for the positive class
        val index1 = model.getLabels.indexOf(1)
        val scoredEntries = this.scoredEntries(text, span)

        // count the number of times an entry "wins" according to the pair-wise classifier
        val wins = Array.fill(scoredEntries.length)(0f)
        for (i <- scoredEntries.indices; j <- scoredEntries.indices; if i != j) {
          val probabilities = Array(0.0, 0.0)
          Linear.predictProbability(model, pairFeatures(scoredEntries(i), scoredEntries(j)), probabilities)

          // only count a "win" if the model is confident (threshold set manually using train/dev set)
          if (probabilities(index1) > 0.8)
            wins(i) += 1
        }

        // sort entries by the number of wins
        scoredEntries.zipWithIndex.map{
          case ((entry, _), i) => (entry, wins(i))
        }.sortBy(-_._2)
    }
  }

  def save(modelPath: Path): Unit = {
    for (model <- linearModel) {
      val writer = Files.newBufferedWriter(modelPath)
      model.save(writer)
      writer.close()
    }
  }
}
