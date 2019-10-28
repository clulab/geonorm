organization := "org.clulab"
name := "geonorm"

scalaVersion := "2.12.8"
crossScalaVersions := List("2.11.12", "2.12.8", "2.13.0")
scalacOptions := Seq("-unchecked", "-deprecation")

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

// needed or tensorflow fails with "Cannot register 2 metrics with the same name"
Test / fork := true

// Additional metadata required by Sonatype OSS
// https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html

organization := "org.clulab"
organizationName := "computational language understanding lab"
organizationHomepage := Some(url("http://clulab.org/"))

scmInfo := Some(
  ScmInfo(
    url("https://github.com/clulab/geonorm/"),
    "scm:git@github.com:clulab/geonorm.git"
  )
)
developers := List(
  Developer(
    id    = "steven.bethard",
    name  = "Steven Bethard",
    email = "bethard@email.arizona.edu",
    url   = url("https://bethard.faculty.arizona.edu/")
  )
)

description := "Geographical name normalization (a.k.a. toponym resolution)"
licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
homepage := Some(url("https://github.com/clulab/geonorm/"))

// Remove all additional repository other than Maven Central from POM
pomIncludeRepository := { _ => false }
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
publishMavenStyle := true
