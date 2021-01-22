/*
scalafmt: {
  maxColumn = 150
  align.tokens.add = [
    { code = ":=", owner = "Term.ApplyInfix" }
    { code = "+=", owner = "Term.ApplyInfix" }
    { code = "++=", owner = "Term.ApplyInfix" }
    { code = "~=", owner = "Term.ApplyInfix" }
  ]
}
 */

val scalacScapegoatVersion = "1.4.6"
val scalaCompilerVersion   = "2.13.3"

val akkaHttpVersion                 = "10.2.1"
val akkaHttpCirceVersion            = "1.35.2"
val akkaCorsVersion                 = "1.1.0"
val akkaPersistenceCassandraVersion = "1.0.4"
val akkaPersistenceJdbcVersion      = "4.0.0"
val akkaVersion                     = "2.6.10"
val alpakkaVersion                  = "2.0.2"
val apacheCompressVersion           = "1.20"
val awsSdkVersion                   = "2.15.35"
val byteBuddyAgentVersion           = "1.10.17"
val betterMonadicForVersion         = "0.3.1"
val catsEffectVersion               = "2.2.0"
val catsRetryVersion                = "0.3.2"
val catsVersion                     = "2.2.0"
val circeVersion                    = "0.13.0"
val classgraphVersion               = "4.8.90"
val declineVersion                  = "1.3.0"
val distageVersion                  = "0.10.19"
val dockerTestKitVersion            = "0.9.9"
val doobieVersion                   = "0.9.4"
val fs2Version                      = "2.4.6"
val http4sVersion                   = "0.21.13"
val h2Version                       = "1.4.200"
val jenaVersion                     = "3.16.0"
val jsonldjavaVersion               = "0.13.2"
val kamonVersion                    = "2.1.9"
val kanelaAgentVersion              = "1.0.7"
val kindProjectorVersion            = "0.11.1"
val kryoVersion                     = "2.0.1"
val logbackVersion                  = "1.2.3"
val magnoliaVersion                 = "0.17.0"
val mockitoVersion                  = "1.16.3"
val monixVersion                    = "3.3.0"
val monixBioVersion                 = "1.1.0"
val nimbusJoseJwtVersion            = "9.1.3"
val pureconfigVersion               = "0.14.0"
val scalaLoggingVersion             = "3.9.2"
val scalateVersion                  = "1.9.6"
val scalaTestVersion                = "3.2.3"
val slickVersion                    = "3.3.3"
val streamzVersion                  = "0.12"
val topBraidVersion                 = "1.3.2"
val uuidGeneratorVersion            = "4.0.1"

lazy val akkaActorTyped           = "com.typesafe.akka" %% "akka-actor-typed"            % akkaVersion
lazy val akkaClusterTyped         = "com.typesafe.akka" %% "akka-cluster-typed"          % akkaVersion
lazy val akkaClusterShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion
lazy val akkaDistributedData      = "com.typesafe.akka" %% "akka-distributed-data"       % akkaVersion

lazy val akkaHttp        = "com.typesafe.akka" %% "akka-http"         % akkaHttpVersion
lazy val akkaHttpCore    = "com.typesafe.akka" %% "akka-http-core"    % akkaHttpVersion
lazy val akkaHttpCirce   = "de.heikoseeberger" %% "akka-http-circe"   % akkaHttpCirceVersion
lazy val akkaHttpCors    = "ch.megard"         %% "akka-http-cors"    % akkaCorsVersion
lazy val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion
lazy val akkaHttpXml     = "com.typesafe.akka" %% "akka-http-xml"     % akkaHttpVersion

lazy val akkaPersistenceTyped   = "com.typesafe.akka" %% "akka-persistence-typed"   % akkaVersion
lazy val akkaPersistenceTestKit = "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion

lazy val akkaPersistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaPersistenceCassandraVersion

lazy val akkaPersistenceJdbc = Seq(
  "com.lightbend.akka" %% "akka-persistence-jdbc" % akkaPersistenceJdbcVersion,
  "com.typesafe.slick" %% "slick"                 % slickVersion,
  "com.typesafe.slick" %% "slick-hikaricp"        % slickVersion
)

lazy val akkaPersistenceQuery = "com.typesafe.akka"          %% "akka-persistence-query"          % akkaVersion
lazy val akkaSlf4j            = "com.typesafe.akka"          %% "akka-slf4j"                      % akkaVersion
lazy val akkaStream           = "com.typesafe.akka"          %% "akka-stream"                     % akkaVersion
lazy val akkaTestKit          = "com.typesafe.akka"          %% "akka-testkit"                    % akkaVersion
lazy val akkaTestKitTyped     = "com.typesafe.akka"          %% "akka-actor-testkit-typed"        % akkaVersion
lazy val alpakkaFiles         = "com.lightbend.akka"         %% "akka-stream-alpakka-file"        % alpakkaVersion
lazy val alpakkaSse           = "com.lightbend.akka"         %% "akka-stream-alpakka-sse"         % alpakkaVersion
lazy val alpakkaS3            = "com.lightbend.akka"         %% "akka-stream-alpakka-s3"          % alpakkaVersion
lazy val apacheCompress       = "org.apache.commons"          % "commons-compress"                % apacheCompressVersion
lazy val awsSdk               = "software.amazon.awssdk"      % "s3"                              % awsSdkVersion
lazy val betterMonadicFor     = "com.olegpy"                 %% "better-monadic-for"              % betterMonadicForVersion
lazy val byteBuddyAgent       = "net.bytebuddy"               % "byte-buddy-agent"                % byteBuddyAgentVersion
lazy val catsCore             = "org.typelevel"              %% "cats-core"                       % catsVersion
lazy val catsEffect           = "org.typelevel"              %% "cats-effect"                     % catsEffectVersion
lazy val catsEffectRetry      = "com.github.cb372"           %% "cats-retry-cats-effect"          % catsRetryVersion
lazy val catsRetry            = "com.github.cb372"           %% "cats-retry-core"                 % catsRetryVersion
lazy val circeCore            = "io.circe"                   %% "circe-core"                      % circeVersion
lazy val circeGeneric         = "io.circe"                   %% "circe-generic"                   % circeVersion
lazy val circeGenericExtras   = "io.circe"                   %% "circe-generic-extras"            % circeVersion
lazy val circeLiteral         = "io.circe"                   %% "circe-literal"                   % circeVersion
lazy val circeOptics          = "io.circe"                   %% "circe-optics"                    % circeVersion
lazy val circeParser          = "io.circe"                   %% "circe-parser"                    % circeVersion
lazy val classgraph           = "io.github.classgraph"        % "classgraph"                      % classgraphVersion
lazy val decline              = "com.monovore"               %% "decline"                         % declineVersion
lazy val distageCore          = "io.7mind.izumi"             %% "distage-core"                    % distageVersion
lazy val distageDocker        = "io.7mind.izumi"             %% "distage-framework-docker"        % distageVersion
lazy val distageTestkit       = "io.7mind.izumi"             %% "distage-testkit-scalatest"       % distageVersion
lazy val doobiePostgres       = "org.tpolecat"               %% "doobie-postgres"                 % doobieVersion
lazy val dockerTestKit        = "com.whisk"                  %% "docker-testkit-scalatest"        % dockerTestKitVersion
lazy val dockerTestKitImpl    = "com.whisk"                  %% "docker-testkit-impl-docker-java" % dockerTestKitVersion
lazy val fs2                  = "co.fs2"                     %% "fs2-core"                        % fs2Version
lazy val h2                   = "com.h2database"              % "h2"                              % h2Version
lazy val http4sCirce          = "org.http4s"                 %% "http4s-circe"                    % http4sVersion
lazy val http4sClient         = "org.http4s"                 %% "http4s-blaze-client"             % http4sVersion
lazy val http4sDsl            = "org.http4s"                 %% "http4s-dsl"                      % http4sVersion
lazy val jenaArq              = "org.apache.jena"             % "jena-arq"                        % jenaVersion
lazy val jsonldjava           = "com.github.jsonld-java"      % "jsonld-java"                     % jsonldjavaVersion
lazy val kamonCore            = "io.kamon"                   %% "kamon-core"                      % kamonVersion
lazy val kanelaAgent          = "io.kamon"                    % "kanela-agent"                    % kanelaAgentVersion
lazy val kindProjector        = "org.typelevel"              %% "kind-projector"                  % kindProjectorVersion cross CrossVersion.full
lazy val kryo                 = "io.altoo"                   %% "akka-kryo-serialization"         % kryoVersion
lazy val logback              = "ch.qos.logback"              % "logback-classic"                 % logbackVersion
lazy val magnolia             = "com.propensive"             %% "magnolia"                        % magnoliaVersion
lazy val mockito              = "org.mockito"                %% "mockito-scala"                   % mockitoVersion
lazy val monixBio             = "io.monix"                   %% "monix-bio"                       % monixBioVersion
lazy val monixEval            = "io.monix"                   %% "monix-eval"                      % monixVersion
lazy val nimbusJoseJwt        = "com.nimbusds"                % "nimbus-jose-jwt"                 % nimbusJoseJwtVersion
lazy val pureconfig           = "com.github.pureconfig"      %% "pureconfig"                      % pureconfigVersion
lazy val scalaLogging         = "com.typesafe.scala-logging" %% "scala-logging"                   % scalaLoggingVersion
lazy val scalate              = "org.scalatra.scalate"       %% "scalate-core"                    % scalateVersion
lazy val scalaTest            = "org.scalatest"              %% "scalatest"                       % scalaTestVersion
lazy val streamz              = "com.github.krasserm"        %% "streamz-converter"               % streamzVersion
lazy val topBraidShacl        = "org.topbraid"                % "shacl"                           % topBraidVersion
lazy val uuidGenerator        = "com.fasterxml.uuid"          % "java-uuid-generator"             % uuidGeneratorVersion

val javaSpecificationVersion = SettingKey[String](
  "java-specification-version",
  "The java specification version to be used for source and target compatibility."
)

lazy val checkJavaVersion = taskKey[Unit]("Verifies the current Java version is compatible with the code java version")

lazy val makeProductPage = taskKey[Unit]("Crete product page")

lazy val productPage = project
  .in(file("product-page"))
  .enablePlugins(GhpagesPlugin)
  .settings(shared, compilation)
  .settings(
    name                              := "product-page",
    moduleName                        := "product-page",
    // gh pages settings
    git.remoteRepo                    := "git@github.com:BlueBrain/nexus.git",
    ghpagesNoJekyll                   := true,
    ghpagesBranch                     := "gh-pages",
    makeProductPage                   := {
      import scala.sys.process._
      import java.nio.file.Files
      val log     = streams.value.log
      if (!Files.exists(siteSourceDirectory.value.toPath)) Files.createDirectory(siteSourceDirectory.value.toPath)
      val install = Process(Seq("make", "install"), baseDirectory.value / "src")
      val build   = Process(Seq("make", "build"), baseDirectory.value / "src")
      if ((install #&& build !) == 0) {
        log.success("Product page built.")
      } else {
        log.error("Product page built failed.")
        throw new RuntimeException
      }
    },
    includeFilter in makeSite         := "*.*",
    makeSite                          := makeSite.dependsOn(makeProductPage).value,
    previewFixedPort                  := Some(4000),
    excludeFilter in ghpagesCleanSite := docsFilesFilter(ghpagesRepository.value),
    cleanFiles                       ++= Seq(
      baseDirectory.value / "src" / ".cache",
      siteSourceDirectory.value
    )
  )

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(ParadoxPlugin, ParadoxMaterialThemePlugin, ParadoxSitePlugin, GhpagesPlugin)
  .disablePlugins(ScapegoatSbtPlugin)
  .settings(shared, compilation, assertJavaVersion)
  .settings(ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox))
  .settings(
    name                              := "docs",
    moduleName                        := "docs",
    // paradox settings
    paradoxValidationIgnorePaths     ++= List(
      "http://www.w3.org/2001/XMLSchema.*".r,
      "https://movies.com/movieId/1".r,
      "https://sandbox.bluebrainnexus.io.*".r
    ),
    sourceDirectory in Paradox        := sourceDirectory.value / "main" / "paradox",
    paradoxMaterialTheme in Paradox   := {
      ParadoxMaterialTheme()
        .withColor("light-blue", "cyan")
        .withFavicon("./assets/img/favicon-32x32.png")
        .withLogo("./assets/img/logo.png")
        .withCustomStylesheet("./assets/css/docs.css")
        .withRepository(uri("https://github.com/BlueBrain/nexus"))
        .withSocial(
          uri("https://github.com/BlueBrain"),
          uri("https://gitter.im/BlueBrain/nexus")
        )
        .withCustomJavaScript("./public/js/gtm.js")
        .withCopyright("""Nexus is Open Source and available under the Apache 2 License.<br/>
                         |© 2017-2020 <a href="https://epfl.ch/">EPFL</a> | <a href="https://bluebrain.epfl.ch/">The Blue Brain Project</a>
                         |""".stripMargin)
    },
    paradoxNavigationDepth in Paradox := 4,
    paradoxProperties in Paradox      += ("github.base_url" -> "https://github.com/BlueBrain/nexus/tree/master"),
    paradoxRoots                      := List("docs/index.html"),
    previewPath                       := "docs/index.html",
    previewFixedPort                  := Some(4001),
    // gh pages settings
    includeFilter in ghpagesCleanSite := docsFilesFilter(ghpagesRepository.value),
    git.remoteRepo                    := "git@github.com:BlueBrain/nexus.git",
    ghpagesNoJekyll                   := true,
    ghpagesBranch                     := "gh-pages"
  )

lazy val kernel = project
  .in(file("delta/kernel"))
  .settings(name := "delta-kernel", moduleName := "delta-kernel")
  .settings(shared, compilation, coverage, release, assertJavaVersion)
  .settings(
    javaSpecificationVersion := "1.8",
    libraryDependencies     ++= Seq(
      catsEffectRetry,
      circeParser,
      monixBio,
      kamonCore,
      pureconfig,
      scalaLogging,
      scalate,
      scalaTest % Test
    ),
    addCompilerPlugin(kindProjector),
    coverageFailOnMinimum    := false
  )

lazy val testkit = project
  .dependsOn(kernel)
  .in(file("delta/testkit"))
  .settings(name := "delta-testkit", moduleName := "delta-testkit")
  .settings(shared, compilation, coverage, release, assertJavaVersion)
  .settings(
    javaSpecificationVersion := "1.8",
    libraryDependencies     ++= Seq(
      catsEffectRetry,
      dockerTestKit,
      dockerTestKitImpl,
      doobiePostgres,
      distageDocker,
      distageTestkit,
      monixBio,
      scalate,
      scalaTest
    ),
    addCompilerPlugin(kindProjector)
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(testkit % "test->compile")
  .enablePlugins(UniversalPlugin, JavaAppPackaging, DockerPlugin)
  .settings(shared, compilation, assertJavaVersion, coverage, release, servicePackaging)
  .settings(
    name                 := "cli",
    moduleName           := "cli",
    Docker / packageName := "nexus-cli",
    coverageMinimum      := 70d,
    run / fork           := true,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      catsEffectRetry,
      catsRetry,
      circeGeneric,
      circeParser,
      decline,
      distageCore,
      doobiePostgres,
      http4sCirce,
      http4sClient,
      fs2,
      monixEval,
      pureconfig,
      circeLiteral % Test,
      http4sDsl    % Test,
      jenaArq      % Test
    )
  )

lazy val sourcing = project
  .in(file("delta/sourcing"))
  .dependsOn(kernel, testkit % "test->compile")
  .settings(
    name       := "delta-sourcing",
    moduleName := "delta-sourcing"
  )
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .settings(
    coverageMinimum      := 64,
    libraryDependencies ++= Seq(
      akkaActorTyped,
      akkaClusterTyped,
      akkaClusterShardingTyped,
      akkaPersistenceTyped,
      akkaPersistenceCassandra,
      akkaPersistenceQuery,
      catsCore,
      circeCore,
      circeGenericExtras,
      circeParser,
      distageCore,
      doobiePostgres,
      fs2,
      kryo,
      monixBio,
      streamz,
      akkaPersistenceTestKit % Test,
      akkaSlf4j              % Test,
      logback                % Test
    ) ++ akkaPersistenceJdbc,
    Test / fork          := true
  )

lazy val rdf = project
  .in(file("delta/rdf"))
  .dependsOn(kernel, testkit % "test->compile")
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .settings(
    name       := "delta-rdf",
    moduleName := "delta-rdf"
  )
  .settings(
    javaSpecificationVersion := "1.8",
    libraryDependencies     ++= Seq(
      akkaActorTyped, // Needed to create Uri
      akkaHttpCore,
      catsCore,
      circeParser,
      circeGeneric,
      jenaArq,
      magnolia,
      monixBio,
      topBraidShacl,
      akkaSlf4j   % Test,
      akkaTestKit % Test,
      logback     % Test,
      scalaTest   % Test
    ),
    Test / fork              := true
  )

lazy val sdk = project
  .in(file("delta/sdk"))
  .settings(
    name       := "delta-sdk",
    moduleName := "delta-sdk"
  )
  .dependsOn(kernel, sourcing, rdf % "compile->compile;test->test", testkit % "test->compile")
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .settings(
    coverageFailOnMinimum := false,
    libraryDependencies  ++= Seq(
      akkaClusterTyped,
      akkaDistributedData,
      akkaHttp,
      akkaPersistenceQuery, // To have access to the Offset type
      circeGenericExtras,
      distageCore,
      fs2,
      kryo,
      monixBio,
      streamz,
      akkaTestKitTyped % Test,
      akkaHttpTestKit  % Test,
      scalaTest        % Test,
      mockito          % Test
    ),
    addCompilerPlugin(kindProjector),
    addCompilerPlugin(betterMonadicFor)
  )

lazy val sdkTestkit = project
  .in(file("delta/sdk-testkit"))
  .settings(
    name       := "delta-sdk-testkit",
    moduleName := "delta-sdk-testkit"
  )
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .dependsOn(rdf, sdk % "compile->compile;test->test", testkit)
  .settings(
    libraryDependencies ++= Seq(
      akkaTestKitTyped,
      scalaTest % Test
    ) ++ akkaPersistenceJdbc,
    addCompilerPlugin(betterMonadicFor)
  )

lazy val service = project
  .in(file("delta/service"))
  .settings(
    name       := "delta-service",
    moduleName := "delta-service"
  )
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .dependsOn(rdf, sdk, sdkTestkit % "test->compile;test->test", testkit % "test->compile")
  .settings(compile in Test := (compile in Test).dependsOn(assembly in testPlugin).value)
  .settings(
    libraryDependencies ++= Seq(
      classgraph,
      nimbusJoseJwt,
      akkaSlf4j        % Test,
      akkaTestKitTyped % Test,
      akkaHttpTestKit  % Test,
      h2               % Test,
      logback          % Test,
      scalaTest        % Test
    ),
    addCompilerPlugin(betterMonadicFor),
    Test / fork          := true
  )

lazy val app = project
  .in(file("delta/app"))
  .settings(
    name       := "delta-app",
    moduleName := "delta-app"
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging, JavaAgent, DockerPlugin, BuildInfoPlugin)
  .settings(shared, compilation, servicePackaging, assertJavaVersion, kamonSettings, coverage, release)
  .dependsOn(service, testkit % "test->compile", sdkTestkit % "test->compile;test->test")
  .settings(
    libraryDependencies  ++= Seq(
      akkaDistributedData,
      akkaHttpCors,
      akkaSlf4j,
      logback,
      akkaHttpTestKit  % Test,
      akkaTestKitTyped % Test,
      scalaTest        % Test
    ),
    addCompilerPlugin(betterMonadicFor),
    run / fork            := true,
    buildInfoKeys         := Seq[BuildInfoKey](version),
    buildInfoPackage      := "ch.epfl.bluebrain.nexus.delta.config",
    Docker / packageName  := "nexus-delta",
    Universal / mappings ++= {
      val esFile      = (elasticsearch / assembly).value
      val bgFile      = (blazegraphPlugin / assembly).value
      val storageFile = (storagePlugin / assembly).value
      Seq(
        (esFile, "plugins/" + esFile.getName),
        (bgFile, "plugins/" + bgFile.getName),
        (storageFile, "plugins/" + storageFile.getName)
      )
    }
  )

lazy val testPlugin = project
  .in(file("delta/plugins/test-plugin"))
  .dependsOn(sdk % Provided, testkit % Provided)
  .settings(shared, compilation, noPublish)
  .settings(
    name                          := "delta-test-plugin",
    moduleName                    := "delta-test-plugin",
    assembly / assemblyOutputPath := target.value / "delta-test-plugin.jar",
    assembly / assemblyOption     := (assembly / assemblyOption).value.copy(includeScala = false),
    Test / fork                   := true
  )

lazy val elasticsearch = project
  .in(file("delta/plugins/elasticsearch"))
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk        % Provided,
    sdkTestkit % Test
  )
  .settings(
    name                       := "delta-elasticsearch-plugin",
    moduleName                 := "delta-elasticsearch-plugin",
    assembly / assemblyJarName := "elasticsearch.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.copy(includeScala = false),
    assembly / test            := {}
  )

lazy val blazegraphPlugin = project
  .in(file("delta/plugins/blazegraph"))
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk        % Provided,
    sdkTestkit % "test->compile;test->test"
  )
  .settings(
    name                       := "delta-blazegraph-plugin",
    moduleName                 := "delta-blazegraph-plugin",
    libraryDependencies       ++= Seq(
      akkaSlf4j         % Test,
      dockerTestKit     % Test,
      dockerTestKitImpl % Test,
      h2                % Test,
      logback           % Test,
      scalaTest         % Test
    ),
    addCompilerPlugin(betterMonadicFor),
    assembly / assemblyJarName := "blazegraph.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.copy(includeScala = false),
    assembly / test            := {}
  )

lazy val storagePlugin = project
  .enablePlugins(BuildInfoPlugin)
  .in(file("delta/plugins/storage"))
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk        % Provided,
    sdkTestkit % "test->compile;test->test"
  )
  .settings(
    name                       := "delta-storage-plugin",
    moduleName                 := "delta-storage-plugin",
    libraryDependencies       ++= Seq(
      akkaHttpXml exclude ("com.typesafe.akka", "akka-http_2.13"),
      alpakkaS3 excludeAll (
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13"),
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-http_2.13"),
        ExclusionRule(organization = "org.slf4j", name = "slf4j-api")
      ),
      "io.kamon"       %% "kamon-akka-http" % kamonVersion % Provided,
      akkaSlf4j         % Test,
      akkaHttpTestKit   % Test,
      dockerTestKit     % Test,
      dockerTestKitImpl % Test,
      h2                % Test,
      logback           % Test,
      scalaTest         % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.storage",
    addCompilerPlugin(betterMonadicFor),
    addCompilerPlugin(kindProjector),
    coverageFailOnMinimum      := false, // TODO: Remove this line when coverage increases
    assembly / assemblyJarName := "storage.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.copy(includeScala = false),
    assembly / test            := {}
  )

lazy val plugins = project
  .in(file("delta/plugins"))
  .settings(noPublish)
  .aggregate(elasticsearch, blazegraphPlugin, storagePlugin, testPlugin)

lazy val delta = project
  .in(file("delta"))
  .settings(noPublish)
  .aggregate(kernel, testkit, sourcing, rdf, sdk, sdkTestkit, service, app, plugins)

lazy val cargo = taskKey[(File, String)]("Run Cargo to build 'nexus-fixer'")

lazy val docsFiles =
  Set("_template/", "assets/", "contexts/", "docs/", "lib/", "CNAME", "paradox.json", "partials/", "public/", "schemas/", "search/", "project/")

def docsFilesFilter(repo: File) =
  new FileFilter {
    def accept(repoFile: File) = docsFiles.exists(file => repoFile.getCanonicalPath.startsWith((repo / file).getCanonicalPath))
  }

lazy val storage = project
  .in(file("storage"))
  .enablePlugins(UniversalPlugin, JavaAppPackaging, JavaAgent, DockerPlugin, BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, kamonSettings, storageAssemblySettings, coverage, release, servicePackaging)
  .dependsOn(rdf)
  .settings(cargo := {
    import scala.sys.process._

    val log = streams.value.log
    val cmd = Process(Seq("cargo", "build", "--release"), baseDirectory.value / "permissions-fixer")
    if ((cmd !) == 0) {
      log.success("Cargo build successful.")
      (baseDirectory.value / "permissions-fixer" / "target" / "release" / "nexus-fixer") -> "bin/nexus-fixer"
    } else {
      log.error("Cargo build failed.")
      throw new RuntimeException
    }
  })
  .settings(
    name                     := "storage",
    moduleName               := "storage",
    buildInfoKeys            := Seq[BuildInfoKey](version),
    buildInfoPackage         := "ch.epfl.bluebrain.nexus.storage.config",
    Docker / packageName     := "nexus-storage",
    javaSpecificationVersion := "1.8",
    libraryDependencies     ++= Seq(
      apacheCompress,
      akkaHttp,
      akkaHttpCirce,
      akkaStream,
      akkaSlf4j,
      alpakkaFiles,
      catsCore,
      catsEffect,
      circeCore,
      circeGenericExtras,
      logback,
      monixEval,
      scalaLogging,
      akkaHttpTestKit % Test,
      akkaTestKit     % Test,
      mockito         % Test,
      scalaTest       % Test
    ),
    cleanFiles              ++= Seq(
      baseDirectory.value / "permissions-fixer" / "target" / "**",
      baseDirectory.value / "nexus-storage.jar"
    ),
    Test / testOptions       += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", "target/test-reports"),
    Test / parallelExecution := false,
    mappings in Universal    := {
      (mappings in Universal).value :+ cargo.value
    }
  )

lazy val dockerCompose = Seq(
  composeFile := "tests/docker/docker-compose-cassandra.yml"
)

lazy val tests = project
  .in(file("tests"))
  .dependsOn(testkit)
  .enablePlugins(DockerComposePlugin)
  .settings(noPublish ++ dockerCompose)
  .settings(shared, compilation, coverage, release)
  .settings(
    name                      := "tests",
    moduleName                := "tests",
    coverageFailOnMinimum     := false,
    libraryDependencies      ++= Seq(
      akkaHttp,
      akkaStream,
      circeOptics,
      fs2,
      logback,
      monixBio,
      scalaLogging,
      akkaTestKit     % Test,
      akkaHttpTestKit % Test,
      awsSdk          % Test,
      scalaTest       % Test,
      akkaSlf4j       % Test,
      alpakkaSse      % Test,
      uuidGenerator   % Test
    ),
    parallelExecution in Test := false,
    Test / testOptions        += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", "target/test-reports")
  )

lazy val root = project
  .in(file("."))
  .settings(name := "nexus", moduleName := "nexus")
  .settings(noPublish)
  .aggregate(docs, cli, delta, storage, tests)

lazy val noPublish = Seq(publishLocal := {}, publish := {}, publishArtifact := false)

lazy val assertJavaVersion =
  Seq(
    checkJavaVersion   := {
      val current  = VersionNumber(sys.props("java.specification.version"))
      val required = VersionNumber(javaSpecificationVersion.value)
      assert(CompatibleJavaVersion(current, required), s"Java '$required' or above required; current '$current'")
    },
    compile in Compile := (compile in Compile).dependsOn(checkJavaVersion).value
  )

lazy val shared = Seq(
  organization := "ch.epfl.bluebrain.nexus",
  resolvers   ++= Seq(
    Resolver.bintrayRepo("bbp", "nexus-releases"),
    Resolver.bintrayRepo("bbp", "nexus-snapshots"),
    Resolver.bintrayRepo("streamz", "maven")
  )
)

lazy val kamonSettings = Seq(
  libraryDependencies ++= Seq(
    "io.kamon" %% "kamon-akka"           % kamonVersion,
    "io.kamon" %% "kamon-akka-http"      % kamonVersion,
    // "io.kamon" %% "kamon-cassandra"      % kamonVersion, // does not support v4.x of the cassandra driver
    "io.kamon" %% "kamon-core"           % kamonVersion,
    "io.kamon" %% "kamon-executors"      % kamonVersion,
    "io.kamon" %% "kamon-jaeger"         % kamonVersion,
    "io.kamon" %% "kamon-jdbc"           % kamonVersion,
    "io.kamon" %% "kamon-logback"        % kamonVersion,
    "io.kamon" %% "kamon-prometheus"     % kamonVersion,
    "io.kamon" %% "kamon-scala-future"   % kamonVersion,
    "io.kamon" %% "kamon-status-page"    % kamonVersion,
    "io.kamon" %% "kamon-system-metrics" % kamonVersion
  ),
  javaAgents           += kanelaAgent
)

lazy val storageAssemblySettings = Seq(
  test in assembly                  := {},
  assemblyOutputPath in assembly    := baseDirectory.value / "nexus-storage.jar",
  assemblyMergeStrategy in assembly := {
    case PathList("org", "apache", "commons", "logging", xs @ _*)        => MergeStrategy.last
    case PathList("akka", "remote", "kamon", xs @ _*)                    => MergeStrategy.last
    case PathList("kamon", "instrumentation", "akka", "remote", xs @ _*) => MergeStrategy.last
    case x if x.endsWith("module-info.class")                            => MergeStrategy.discard
    case x                                                               =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }
)

lazy val discardModuleInfoAssemblySettings = Seq(
  assemblyMergeStrategy in assembly := {
    case x if x.contains("io.netty.versions.properties") => MergeStrategy.discard
    case "module-info.class"                             => MergeStrategy.discard
    case x                                               =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }
)

lazy val compilation = {
  import sbt.Keys._
  import sbt._

  Seq(
    scalaVersion                     := scalaCompilerVersion,
    scalacOptions                    ~= { options: Seq[String] => options.filterNot(Set("-Wself-implicit")) },
    javaSpecificationVersion         := "11",
    javacOptions                    ++= Seq(
      "-source",
      javaSpecificationVersion.value,
      "-target",
      javaSpecificationVersion.value,
      "-Xlint"
    ),
    scalacOptions in (Compile, doc) ++= Seq("-no-link-warnings"),
    javacOptions in (Compile, doc)   := Seq("-source", javaSpecificationVersion.value),
    autoAPIMappings                  := true,
    apiMappings                      += {
      val scalaDocUrl = s"http://scala-lang.org/api/${scalaVersion.value}/"
      ApiMappings.apiMappingFor((fullClasspath in Compile).value)("scala-library", scalaDocUrl)
    },
    Scapegoat / dependencyClasspath  := (dependencyClasspath in Compile).value
  )
}

lazy val coverage = Seq(
  coverageMinimum       := 80,
  coverageFailOnMinimum := true
)

lazy val release = Seq(
  bintrayOrganization                      := Some("bbp"),
  bintrayRepository                        := {
    import ch.epfl.scala.sbt.release.ReleaseEarly.Defaults
    if (Defaults.isSnapshot.value) "nexus-snapshots"
    else "nexus-releases"
  },
  sources in (Compile, doc)                := Seq.empty,
  publishArtifact in packageDoc            := false,
  publishArtifact in (Compile, packageSrc) := true,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Test, packageBin)    := false,
  publishArtifact in (Test, packageDoc)    := false,
  publishArtifact in (Test, packageSrc)    := false,
  publishMavenStyle                        := true,
  pomIncludeRepository                     := Function.const(false),
  // removes compile time only dependencies from the resulting pom
  pomPostProcess                           := { node =>
    XmlTransformer.transformer(moduleFilter("org.scoverage") | moduleFilter("com.sksamuel.scapegoat")).transform(node).head
  }
)

lazy val servicePackaging = {
  import com.typesafe.sbt.packager.Keys._
  import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{dockerChmodType, Docker}
  import com.typesafe.sbt.packager.docker.{DockerChmodType, DockerVersion}
  import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
  Seq(
    mappings in Universal += (WaitForIt.download(target.value) -> "bin/wait-for-it.sh"),
    // docker publishing settings
    Docker / maintainer   := "Nexus Team <noreply@epfl.ch>",
    Docker / version      := {
      import ch.epfl.scala.sbt.release.ReleaseEarly.Defaults
      if (Defaults.isSnapshot.value) "latest"
      else version.value
    },
    Docker / daemonUser   := "nexus",
    dockerBaseImage       := "adoptopenjdk:11-jre-hotspot",
    dockerExposedPorts    := Seq(8080, 2552),
    dockerUsername        := Some("bluebrain"),
    dockerUpdateLatest    := false,
    dockerChmodType       := DockerChmodType.UserGroupWriteExecute,
    dockerVersion         := Some(
      DockerVersion(19, 3, 5, Some("ce"))
    ) // forces the version because gh-actions version is 3.0.x which is not recognized to support multistage
  )
}

inThisBuild(
  Seq(
    scapegoatVersion              := scalacScapegoatVersion,
    scapegoatDisabledInspections  := Seq(
      "AsInstanceOf",
      "ClassNames",
      "IncorrectlyNamedExceptions",
      "ObjectNames",
      "RedundantFinalModifierOnCaseClass",
      "RedundantFinalModifierOnMethod",
      "VariableShadowing"
    ),
    homepage                      := Some(url("https://github.com/BlueBrain/nexus-commons")),
    licenses                      := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scmInfo                       := Some(ScmInfo(url("https://github.com/BlueBrain/nexus-commons"), "scm:git:git@github.com:BlueBrain/nexus-commons.git")),
    developers                    := List(
      Developer("bogdanromanx", "Bogdan Roman", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("umbreak", "Didac Montero Mendez", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("wwajerowicz", "Wojtek Wajerowicz", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("imsdu", "Simon Dumas", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/"))
    ),
    // These are the sbt-release-early settings to configure
    releaseEarlyWith              := BintrayPublisher,
    releaseEarlyNoGpg             := true,
    releaseEarlyEnableSyncToMaven := false
  )
)

Global / excludeLintKeys += packageDoc / publishArtifact
Global / excludeLintKeys += tests / composeFile
Global / excludeLintKeys += docs / paradoxRoots
Global / excludeLintKeys += docs / Paradox / paradoxNavigationDepth

addCommandAlias("review", ";clean;scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;coverage;scapegoat;test;coverageReport;coverageAggregate")
addCommandAlias(
  "deltaReview",
  ";delta/clean;delta/scalafmtCheck;delta/test:scalafmtCheck;scalafmtSbtCheck;coverage;delta/scapegoat;delta/test;delta/coverageReport;delta/coverageAggregate"
)
addCommandAlias("build-docs", ";docs/clean;docs/makeSite")
addCommandAlias("preview-docs", ";docs/clean;docs/previewSite")
addCommandAlias("build-product-page", ";productPage/clean;productPage/makeSite")
addCommandAlias("preview-product-page", ";productPage/clean;productPage/previewSite")
