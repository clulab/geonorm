name := "geonorm"
organization := "org.clulab"

scalaVersion := "2.12.8"

libraryDependencies ++= {
  val luceneVer = "6.6.6"

  Seq(
    "org.apache.commons" %  "commons-io"              % "1.3.2",
    "org.tensorflow"     %  "tensorflow"              % "1.12.0",
    "org.clulab"         %  "geonorm-models"          % "0.9.5",
    "de.bwaldvogel"      %  "liblinear"               % "2.30",
    "org.apache.lucene"  %  "lucene-core"             % luceneVer,
    "org.apache.lucene"  %  "lucene-analyzers-common" % luceneVer,
    "org.apache.lucene"  %  "lucene-queryparser"      % luceneVer,
    "org.apache.lucene"  %  "lucene-grouping"         % luceneVer,
    "org.scalatest"      %% "scalatest"               % "3.0.8"    % Test,
  )
}
