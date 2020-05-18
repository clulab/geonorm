ThisBuild / organization := "org.clulab"
ThisBuild / organizationName := "computational language understanding lab"
ThisBuild / organizationHomepage := Some(url("http://clulab.org/"))
ThisBuild / homepage := Some(url("https://github.com/clulab/geonorm/"))
ThisBuild / scalaVersion := "2.12.8"
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/clulab/geonorm/"),
    "scm:git@github.com:clulab/geonorm.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id    = "steven.bethard",
    name  = "Steven Bethard",
    email = "bethard@email.arizona.edu",
    url   = url("https://bethard.faculty.arizona.edu/")
  )
)
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
// Publish as in Maven Central
ThisBuild / publishMavenStyle := true

// Allows different modules to have different versions.
// NOTE: if prefixed by "ThisBuild / ", this setting doesn't work.
releaseUseGlobalVersion := false

lazy val geonorm = (project in file(".")).settings(
  name := "geonorm",
  description := "Geographical name normalization (a.k.a. toponym resolution)",
  crossScalaVersions := List("2.11.12", "2.12.11", "2.13.1"),
  releaseCrossBuild := true,
  scalacOptions := Seq("-unchecked", "-deprecation"),
  resolvers += "Artifactory" at "http://artifactory.cs.arizona.edu:8081/artifactory/sbt-release-local/",
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
      "org.scalatest"      %% "scalatest"               % "3.0.8"                  % Test,
      "org.clulab"         %  "geonames"                % "1.0.0+20200404T005315Z" % Test,
    )
  },
  // needed or tensorflow fails with "Cannot register 2 metrics with the same name"
  Test / fork := true,
  // Store credentials in ~/.sbt/.sonatype_credentials
  credentials += Credentials(Path.userHome / ".sbt" / ".sonatype_credentials"),
  // publish to OSS Sonatype
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
)

lazy val geonames = project.dependsOn(geonorm % "compile-internal").settings(
  name := "geonames",
  description := "Lucene index of GeoNames data from http://download.geonames.org/export/dump/",
  // Store credentials in ~/.sbt/.artifactory_credentials as:
  // realm=Artifactory Realm
  // host=<host>
  // user=<user>
  // password=<password>
  crossPaths := false, // This is a resource only and is independent of Scala version.
  publishArtifact in (Compile, packageBin) := true, // Do include the resources.
  publishArtifact in (Compile, packageDoc) := false, // There is no documentation.
  publishArtifact in (Compile, packageSrc) := false, // There is no source code.
  publishArtifact in (Test, packageBin) := false,
  credentials += Credentials(Path.userHome / ".sbt" / ".artifactory_credentials"),
  publishTo := {
    val artifactory = "http://artifactory.cs.arizona.edu:8081/artifactory/"
    val repository = "sbt-release-local"
    val details = if (isSnapshot.value) ";build.timestamp=" + new java.util.Date().getTime else ""
    val location = artifactory + repository + details
    Some("Artifactory Realm" at location)
  },
  Compile / resourceGenerators += (Def.taskDyn {
    import java.net.URL
    import java.time.{Instant, ZoneOffset}
    import java.time.format.DateTimeFormatter
    import java.nio.file.{Files,Paths,StandardOpenOption}
    import java.nio.channels.FileChannel
    import java.util.zip.ZipFile

    val log = streams.value.log

    // define where the GeoNames index should be built
    val indexPath = ((Compile / resourceManaged).value / "org" / "clulab" / "geonames" / "index").toPath

    // get the last modified date from the version.sbt
    val versionPath = Paths.get("geonames","version.sbt")
    val versionText = new String(Files.readAllBytes(versionPath))
    val versionRegex = "([.\\d]+)[+]([^\"]*)".r
    val dateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX")
    val Some(versionMatch) = versionRegex.findFirstMatchIn(versionText)
    val sbtVersion = versionMatch.group(1)

    // open a connection and determine the last modified time
    val connection = new URL("http://download.geonames.org/export/dump/allCountries.zip").openConnection
    val urlLastModified = Instant.ofEpochMilli(connection.getLastModified)
    val timestamp = dateTimeFormatter.format(urlLastModified.atZone(ZoneOffset.UTC))

    // construct filenames from timestamp
    val allCountriesZipPath = Paths.get("geonames",s"allCountries-$timestamp.zip")
    val allCountriesTxtPath = Paths.get("geonames", s"allCountries-$timestamp.txt")

    // check environment for a local file that should be appended to the GeoNames .txt file
    val extraTxtPathOption = sys.env.get("GEONAMES_EXTRA").map(x => Paths.get(x))

    // if there is an up-to-date allCountries.txt already, use that
    if (Files.exists(allCountriesTxtPath)) {
      log.info(s"Using existing $allCountriesTxtPath")
    }
    // otherwise, download and extract a new allCountries.zip, and update the version date in pom.xml
    else {
      // download the zip file
      log.info(s"Downloading GeoNames data to $allCountriesZipPath")
      Files.copy(connection.getInputStream, allCountriesZipPath)

      // update version.sbt
      val newVersionText = versionRegex.replaceFirstIn(versionText, s"$sbtVersion+$timestamp")
      log.info(s"Writing new version $newVersionText")
      Files.write(versionPath, newVersionText.getBytes("UTF-8"))

      // unzip the .zip file
      log.info(s"Extracting $allCountriesTxtPath")
      val zipFile = new ZipFile(allCountriesZipPath.toFile)
      try {
        val zipEntry = zipFile.getEntry("allCountries.txt")
        Files.copy(zipFile.getInputStream(zipEntry), allCountriesTxtPath)
      } finally {
        zipFile.close()
      }
    }


    // if there is a new GeoNames timestamp, a local file to be concatenated, or no index has yet been built,
    // invoke GeoNamesIndex to create one
    import sbt.util.CacheImplicits._
    val doBuildIndex = Tracked.inputChanged(streams.value.cacheStoreFactory.make("geonames-timestamp")){
      (changed: Boolean, _: String) =>
        changed || extraTxtPathOption.isDefined || !Files.exists(indexPath) || Files.list(indexPath).count() == 0
    }
    if (doBuildIndex(timestamp)) {
      val inputTxtPath = extraTxtPathOption match {
        case None => allCountriesTxtPath
        case Some(extraTxtPath) =>
          val extraTxtName = extraTxtPath.getFileName.toString.replaceAll("[.]txt$|[\\W_]+", "")

          // update version.sbt
          val newVersionText = versionRegex.replaceFirstIn(versionText, s"$sbtVersion+$timestamp.$extraTxtName")
          log.info(s"Writing new version $newVersionText")
          Files.write(versionPath, newVersionText.getBytes("UTF-8"))

          // concatenate the GeoNames .txt file and the extra .txt file
          val combinedTxtPath = Paths.get("geonames", s"allCountries-$timestamp+$extraTxtName.txt")
          log.info(s"Writing concatenated input file $combinedTxtPath")
          val combinedTxtChannel = FileChannel.open(combinedTxtPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
          val allCountriesTxtChannel = FileChannel.open(allCountriesTxtPath, StandardOpenOption.READ)
          val extraTxtChannel = FileChannel.open(extraTxtPath, StandardOpenOption.READ)
          val end = combinedTxtChannel.transferFrom(allCountriesTxtChannel, 0, allCountriesTxtChannel.size)
          combinedTxtChannel.transferFrom(extraTxtChannel, end, extraTxtChannel.size)
          combinedTxtPath
      }

      log.info(s"Creating index at $indexPath")
      val command = s" org.clulab.geonorm.GeoNamesIndex index $indexPath $inputTxtPath"
      Def.task {
        (geonorm / Compile / runMain).toTask(command).value
        indexPath.toFile.listFiles.toSeq
      }
    }
    // otherwise, use the existing index
    else {
      log.info(s"Using existing index at $indexPath")
      Def.task {
        indexPath.toFile.listFiles.toSeq
      }
    }
  }).taskValue
)
