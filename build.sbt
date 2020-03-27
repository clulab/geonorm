organization := "org.clulab"
name := "geonorm"

scalaVersion := "2.12.8"
crossScalaVersions := List("2.11.12", "2.12.8", "2.13.0")
releaseCrossBuild := true
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
credentials += Credentials(Path.userHome / ".sbt" / ".sonatype_credentials")

val indexGeoNames = taskKey[Unit]("Creates an index of the current GeoNames database")
indexGeoNames := (Def.taskDyn {
  import java.net.URL
  import java.time.{Instant, ZoneOffset}
  import java.time.format.DateTimeFormatter
  import java.nio.file.{Files,Paths}
  import java.util.zip.ZipFile

  // find the last modified date from the pom.xml
  val pomPath = Paths.get("geonames-index", "pom.xml")
  val pomText = new String(Files.readAllBytes(pomPath))
  val pomVersionRegex = "<version>([.\\d]+)[+]([^<]*)".r
  val dateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX")
  val Some(versionMatch) = pomVersionRegex.findFirstMatchIn(pomText)
  val pomVersion = versionMatch.group(1)
  val pomLastModified = Instant.from(dateTimeFormatter.parse(versionMatch.group(2)))

  // open a connection and determine the last modified time
  val connection = new URL("http://download.geonames.org/export/dump/allCountries.zip").openConnection
  val urlLastModified = Instant.ofEpochMilli(connection.getLastModified)
  val timestamp = dateTimeFormatter.format(urlLastModified.atZone(ZoneOffset.UTC))

  // construct filenames from timestamp
  val allCountriesZipPath = Paths.get("geonames-index", s"allCountries-$timestamp.zip")
  val allCountriesTxtPath = Paths.get("geonames-index", s"allCountries-$timestamp.txt")

  // if there is an up-to-date allCountries.zip already downloaded, use that
  if (Files.exists(allCountriesZipPath)) {
    println(s"Using existing $allCountriesZipPath last modified on $pomLastModified")
  }
  // otherwise, download and extract a new allCountries.zip, and update the version date in pom.xml
  else {
    // download the zip file
    println(s"Downloading GeoNames data to $allCountriesZipPath")
    Files.copy(connection.getInputStream, allCountriesZipPath)

    // update the version in pom.xml
    val newPomText = pomVersionRegex.replaceFirstIn(pomText, s"<version>$pomVersion+$timestamp")
    Files.write(pomPath, newPomText.getBytes("UTF-8"))

    // unzip the .zip file
    println(s"Extracting $allCountriesTxtPath")
    val zipFile = new ZipFile(allCountriesZipPath.toFile)
    try {
      val zipEntry = zipFile.getEntry("allCountries.txt")
      Files.copy(zipFile.getInputStream(zipEntry), allCountriesTxtPath)
    } finally {
      zipFile.close()
    }
  }

  // compute the command to pass to sbt's runMain to run the GeoNamesIndex class
  val indexPath = Paths.get(
    "geonames-index", "src", "main", "resources", "org", "clulab", "geonames", "index")
  val command = s" org.clulab.geonorm.GeoNamesIndex index $indexPath $allCountriesTxtPath"
  Def.task {
    (Compile / runMain).toTask(command).value
  }
}).value
