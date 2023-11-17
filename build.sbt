import scala.io.Source

/*
scalafmt: {
  maxColumn = 150
  align.tokens."+" = [
    { code = ":=", owner = "Term.ApplyInfix" }
    { code = "+=", owner = "Term.ApplyInfix" }
    { code = "++=", owner = "Term.ApplyInfix" }
    { code = "~=", owner = "Term.ApplyInfix" }
  ]
}
 */

val scalacScapegoatVersion = "2.1.3"
val scalaCompilerVersion   = "2.13.12"

val akkaHttpVersion         = "10.2.10"
val akkaHttpCirceVersion    = "1.39.2"
val akkaCorsVersion         = "1.2.0"
val akkaVersion             = "2.6.21"
val alpakkaVersion          = "3.0.4"
val apacheCompressVersion   = "1.24.0"
val awsSdkVersion           = "2.17.184"
val byteBuddyAgentVersion   = "1.10.17"
val betterMonadicForVersion = "0.3.1"
val caffeineVersion         = "3.1.8"
val catsEffectVersion       = "2.5.5"
val catsRetryVersion        = "2.1.1"
val catsVersion             = "2.10.0"
val circeVersion            = "0.14.6"
val circeOpticsVersion      = "0.15.0"
val circeExtrasVersions     = "0.14.3"
val classgraphVersion       = "4.8.164"
val distageVersion          = "1.0.10"
val doobieVersion           = "0.13.4"
val fs2Version              = "2.5.11"
val googleAuthClientVersion = "1.34.1"
val handleBarsVersion       = "4.3.1"
val hikariVersion           = "5.1.0"
val jenaVersion             = "4.2.0"
val jsonldjavaVersion       = "0.13.6"
val kamonVersion            = "2.6.5"
val kanelaAgentVersion      = "1.0.18"
val kindProjectorVersion    = "0.13.2"
val log4catsVersion         = "1.7.0"
val logbackVersion          = "1.4.11"
val magnoliaVersion         = "1.1.6"
val mockitoVersion          = "1.17.29"
val munitVersion            = "1.0.0-M10"
val nimbusJoseJwtVersion    = "9.37.1"
val postgresJdbcVersion     = "42.6.0"
val pureconfigVersion       = "0.17.4"
val scalaTestVersion        = "3.2.17"
val scalaXmlVersion         = "2.2.0"
val topBraidVersion         = "1.3.2" // 1.4.1 fails to validate some test schemas
val testContainersVersion   = "1.19.1"

lazy val akkaActorTyped = "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion

lazy val akkaHttp        = "com.typesafe.akka" %% "akka-http"         % akkaHttpVersion
lazy val akkaHttpCore    = "com.typesafe.akka" %% "akka-http-core"    % akkaHttpVersion
lazy val akkaHttpCirce   = "de.heikoseeberger" %% "akka-http-circe"   % akkaHttpCirceVersion
lazy val akkaHttpCors    = "ch.megard"         %% "akka-http-cors"    % akkaCorsVersion
lazy val akkaHttpTestKit = "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion
lazy val akkaHttpXml     = "com.typesafe.akka" %% "akka-http-xml"     % akkaHttpVersion

lazy val akkaSlf4j          = "com.typesafe.akka"            %% "akka-slf4j"               % akkaVersion
lazy val akkaStream         = "com.typesafe.akka"            %% "akka-stream"              % akkaVersion
lazy val akkaTestKit        = "com.typesafe.akka"            %% "akka-testkit"             % akkaVersion
lazy val akkaTestKitTyped   = "com.typesafe.akka"            %% "akka-actor-testkit-typed" % akkaVersion
lazy val alpakkaFile        = "com.lightbend.akka"           %% "akka-stream-alpakka-file" % alpakkaVersion
lazy val alpakkaSse         = "com.lightbend.akka"           %% "akka-stream-alpakka-sse"  % alpakkaVersion
lazy val alpakkaS3          = "com.lightbend.akka"           %% "akka-stream-alpakka-s3"   % alpakkaVersion
lazy val apacheCompress     = "org.apache.commons"            % "commons-compress"         % apacheCompressVersion
lazy val awsSdk             = "software.amazon.awssdk"        % "s3"                       % awsSdkVersion
lazy val betterMonadicFor   = "com.olegpy"                   %% "better-monadic-for"       % betterMonadicForVersion
lazy val byteBuddyAgent     = "net.bytebuddy"                 % "byte-buddy-agent"         % byteBuddyAgentVersion
lazy val caffeine           = "com.github.ben-manes.caffeine" % "caffeine"                 % caffeineVersion
lazy val catsCore           = "org.typelevel"                %% "cats-core"                % catsVersion
lazy val catsEffect         = "org.typelevel"                %% "cats-effect"              % catsEffectVersion
lazy val catsEffectLaws     = "org.typelevel"                %% "cats-effect-laws"         % catsEffectVersion
lazy val catsRetry          = "com.github.cb372"             %% "cats-retry"               % catsRetryVersion
lazy val circeCore          = "io.circe"                     %% "circe-core"               % circeVersion
lazy val circeGeneric       = "io.circe"                     %% "circe-generic"            % circeVersion
lazy val circeGenericExtras = "io.circe"                     %% "circe-generic-extras"     % circeExtrasVersions
lazy val circeLiteral       = "io.circe"                     %% "circe-literal"            % circeVersion
lazy val circeOptics        = "io.circe"                     %% "circe-optics"             % circeOpticsVersion
lazy val circeParser        = "io.circe"                     %% "circe-parser"             % circeVersion
lazy val classgraph         = "io.github.classgraph"          % "classgraph"               % classgraphVersion
lazy val distageCore        = "io.7mind.izumi"               %% "distage-core"             % distageVersion
lazy val doobiePostgres     = "org.tpolecat"                 %% "doobie-postgres"          % doobieVersion
lazy val doobie             = Seq(
  doobiePostgres,
  "org.tpolecat"  %% "doobie-hikari" % doobieVersion,
  "com.zaxxer"     % "HikariCP"      % hikariVersion exclude ("org.slf4j", "slf4j-api"),
  "org.postgresql" % "postgresql"    % postgresJdbcVersion
)
lazy val fs2                = "co.fs2"                       %% "fs2-core"                 % fs2Version
lazy val fs2io              = "co.fs2"                       %% "fs2-io"                   % fs2Version
lazy val googleAuthClient   = "com.google.oauth-client"       % "google-oauth-client"      % googleAuthClientVersion
lazy val handleBars         = "com.github.jknack"             % "handlebars"               % handleBarsVersion
lazy val jenaArq            = "org.apache.jena"               % "jena-arq"                 % jenaVersion
lazy val jsonldjava         = "com.github.jsonld-java"        % "jsonld-java"              % jsonldjavaVersion
lazy val kamonAkkaHttp      = "io.kamon"                     %% "kamon-akka-http"          % kamonVersion
lazy val kamonCore          = "io.kamon"                     %% "kamon-core"               % kamonVersion
lazy val kanelaAgent        = "io.kamon"                      % "kanela-agent"             % kanelaAgentVersion
lazy val kindProjector      = "org.typelevel"                %% "kind-projector"           % kindProjectorVersion cross CrossVersion.full
lazy val log4cats           = "org.typelevel"                %% "log4cats-slf4j"           % log4catsVersion
lazy val logback            = "ch.qos.logback"                % "logback-classic"          % logbackVersion
lazy val magnolia           = "com.softwaremill.magnolia1_2" %% "magnolia"                 % magnoliaVersion
lazy val mockito            = "org.mockito"                  %% "mockito-scala"            % mockitoVersion
lazy val munit              = "org.scalameta"                %% "munit"                    % munitVersion
lazy val nimbusJoseJwt      = "com.nimbusds"                  % "nimbus-jose-jwt"          % nimbusJoseJwtVersion
lazy val pureconfig         = "com.github.pureconfig"        %% "pureconfig"               % pureconfigVersion
lazy val pureconfigCats     = "com.github.pureconfig"        %% "pureconfig-cats"          % pureconfigVersion
lazy val scalaReflect       = "org.scala-lang"                % "scala-reflect"            % scalaCompilerVersion
lazy val scalaTest          = "org.scalatest"                %% "scalatest"                % scalaTestVersion
lazy val scalaXml           = "org.scala-lang.modules"       %% "scala-xml"                % scalaXmlVersion
lazy val topBraidShacl      = "org.topbraid"                  % "shacl"                    % topBraidVersion
lazy val testContainers     = "org.testcontainers"            % "testcontainers"           % testContainersVersion

val javaSpecificationVersion = SettingKey[String](
  "java-specification-version",
  "The java specification version to be used for source and target compatibility."
)

lazy val checkJavaVersion = taskKey[Unit]("Verifies the current Java version is compatible with the code java version")

lazy val copyPlugins = taskKey[Unit]("Assembles and copies the plugin files plugins directory")

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(ParadoxMaterialThemePlugin, ParadoxSitePlugin)
  .disablePlugins(ScapegoatSbtPlugin)
  .settings(shared, compilation, assertJavaVersion, noPublish)
  .settings(ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Compile))
  .settings(
    name                             := "docs",
    moduleName                       := "docs",
    // paradox settings
    paradoxValidationIgnorePaths    ++= {
      val source      = Source.fromFile(file("docs/ignore-paths.txt"))
      val ignorePaths = source.getLines().map(_.r).toList
      source.close()
      ignorePaths
    },
    Compile / paradoxMaterialTheme   := {
      ParadoxMaterialTheme()
        .withColor("light-blue", "cyan")
        .withFavicon("./assets/img/favicon-32x32.png")
        .withLogo("./assets/img/logo.png")
        .withCustomStylesheet("./assets/css/docs.css")
        .withRepository(uri("https://github.com/BlueBrain/nexus"))
        .withSocial(
          uri("https://github.com/BlueBrain"),
          uri("https://github.com/BlueBrain/nexus/discussions")
        )
        .withCustomJavaScript("./public/js/gtm.js")
        .withCopyright(s"""Nexus is Open Source and available under the Apache 2 License.<br/>
                         |© 2017-${java.time.LocalDate.now.getYear()} <a href="https://epfl.ch/">EPFL</a>
                         | <a href="https://bluebrain.epfl.ch/">The Blue Brain Project</a>
                         |""".stripMargin)
    },
    Compile / paradoxNavigationDepth := 4,
    Compile / paradoxProperties     ++=
      Map(
        "github.base_url"       -> "https://github.com/BlueBrain/nexus/tree/master",
        "project.version.short" -> "Snapshot",
        "current.url"           -> "https://bluebrainnexus.io/docs/",
        "version.snapshot"      -> "true",
        "git.branch"            -> "master"
      ),
    paradoxRoots                     := List("docs/index.html"),
    previewPath                      := "docs/index.html",
    previewFixedPort                 := Some(4001),
    // copy contexts
    makeSite / mappings             ++= {
      def fileSources(base: File): Seq[File] = (base * "*.json").get
      val contextDirs                        = Seq(
        (rdf / Compile / resourceDirectory).value / "contexts",
        (sdk / Compile / resourceDirectory).value / "contexts",
        (archivePlugin / Compile / resourceDirectory).value / "contexts",
        (blazegraphPlugin / Compile / resourceDirectory).value / "contexts",
        (graphAnalyticsPlugin / Compile / resourceDirectory).value / "contexts",
        (compositeViewsPlugin / Compile / resourceDirectory).value / "contexts",
        (searchPlugin / Compile / resourceDirectory).value / "contexts",
        (elasticsearchPlugin / Compile / resourceDirectory).value / "contexts",
        (storagePlugin / Compile / resourceDirectory).value / "contexts"
      )
      contextDirs.flatMap { dir =>
        fileSources(dir).map(file => file -> s"contexts/${file.getName}")
      }
    }
  )

lazy val kernel = project
  .in(file("delta/kernel"))
  .settings(name := "delta-kernel", moduleName := "delta-kernel")
  .settings(shared, compilation, coverage, release, assertJavaVersion)
  .settings(
    libraryDependencies  ++= Seq(
      akkaActorTyped, // Needed to create content type
      akkaHttpCore,
      caffeine,
      catsCore,
      catsRetry,
      circeCore,
      circeParser,
      handleBars,
      nimbusJoseJwt,
      kamonCore,
      log4cats,
      pureconfig,
      pureconfigCats,
      munit     % Test,
      scalaTest % Test
    ),
    addCompilerPlugin(kindProjector),
    coverageFailOnMinimum := false
  )

lazy val testkit = project
  .dependsOn(kernel)
  .in(file("delta/testkit"))
  .settings(name := "delta-testkit", moduleName := "delta-testkit")
  .settings(shared, compilation, coverage, release, assertJavaVersion)
  .settings(
    coverageMinimumStmtTotal := 0,
    libraryDependencies     ++= Seq(
      akkaActorTyped, // Needed to create Uri
      akkaHttpCore,
      akkaStream,
      alpakkaFile excludeAll (
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13")
      ),
      catsRetry,
      doobiePostgres,
      munit,
      scalaTest,
      testContainers
    ) ++ doobie,
    addCompilerPlugin(kindProjector)
  )

lazy val sourcingPsql = project
  .in(file("delta/sourcing-psql"))
  .dependsOn(rdf, testkit % "test->compile")
  .settings(
    name       := "delta-sourcing-psql",
    moduleName := "delta-sourcing-psql"
  )
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .settings(
    libraryDependencies ++= Seq(
      circeCore,
      circeGenericExtras,
      circeParser,
      classgraph,
      distageCore,
      fs2,
      fs2io,
      munit          % Test,
      catsEffectLaws % Test,
      logback        % Test
    ) ++ doobie,
    Test / fork          := true,
    addCompilerPlugin(kindProjector)
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
    libraryDependencies ++= Seq(
      akkaActorTyped, // Needed to create Uri
      akkaHttpCore,
      catsCore,
      circeParser,
      circeGeneric,
      circeGenericExtras,
      jenaArq,
      jsonldjava,
      magnolia,
      scalaReflect,
      topBraidShacl,
      akkaSlf4j   % Test,
      akkaTestKit % Test,
      logback     % Test,
      scalaTest   % Test
    ),
    Test / fork          := true,
    addCompilerPlugin(betterMonadicFor)
  )

lazy val sdk = project
  .in(file("delta/sdk"))
  .settings(
    name       := "delta-sdk",
    moduleName := "delta-sdk"
  )
  .dependsOn(kernel, sourcingPsql % "compile->compile;test->test", rdf % "compile->compile;test->test", testkit % "test->compile")
  .settings(shared, compilation, assertJavaVersion, coverage, release)
  .settings(
    coverageFailOnMinimum := false,
    libraryDependencies  ++= Seq(
      akkaStream,
      akkaHttp,
      akkaHttpXml exclude ("org.scala-lang.modules", "scala-xml_2.13"),
      scalaXml,
      circeLiteral,
      circeGenericExtras,
      distageCore,
      fs2,
      akkaTestKitTyped % Test,
      akkaHttpTestKit  % Test,
      munit            % Test,
      scalaTest        % Test
    ),
    addCompilerPlugin(kindProjector),
    addCompilerPlugin(betterMonadicFor)
  )

lazy val app = project
  .in(file("delta/app"))
  .settings(
    name       := "delta-app",
    moduleName := "delta-app"
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging, JavaAgent, DockerPlugin, BuildInfoPlugin)
  .settings(shared, compilation, servicePackaging, assertJavaVersion, kamonSettings, coverage, release)
  .dependsOn(sdk % "compile->compile;test->test", testkit % "test->compile")
  .settings(Test / compile := (Test / compile).dependsOn(testPlugin / assembly).value)
  .settings(
    libraryDependencies  ++= Seq(
      akkaHttpCors,
      akkaSlf4j,
      classgraph,
      logback,
      akkaHttpTestKit % Test,
      scalaTest       % Test
    ),
    addCompilerPlugin(betterMonadicFor),
    run / fork            := true,
    buildInfoKeys         := Seq[BuildInfoKey](version),
    buildInfoPackage      := "ch.epfl.bluebrain.nexus.delta.config",
    Docker / packageName  := "nexus-delta",
    copyPlugins           := {
      val esFile              = (elasticsearchPlugin / assembly).value
      val bgFile              = (blazegraphPlugin / assembly).value
      val graphAnalyticsFile  = (graphAnalyticsPlugin / assembly).value
      val storageFile         = (storagePlugin / assembly).value
      val archiveFile         = (archivePlugin / assembly).value
      val compositeViewsFile  = (compositeViewsPlugin / assembly).value
      val searchFile          = (searchPlugin / assembly).value
      val projectDeletionFile = (projectDeletionPlugin / assembly).value
      val jiraFile            = (jiraPlugin / assembly).value
      val pluginsTarget       = target.value / "plugins"
      IO.createDirectory(pluginsTarget)
      IO.copy(
        Set(
          esFile              -> (pluginsTarget / esFile.getName),
          bgFile              -> (pluginsTarget / bgFile.getName),
          graphAnalyticsFile  -> (pluginsTarget / graphAnalyticsFile.getName),
          storageFile         -> (pluginsTarget / storageFile.getName),
          archiveFile         -> (pluginsTarget / archiveFile.getName),
          compositeViewsFile  -> (pluginsTarget / compositeViewsFile.getName),
          searchFile          -> (pluginsTarget / searchFile.getName),
          projectDeletionFile -> (pluginsTarget / projectDeletionFile.getName),
          jiraFile            -> (pluginsTarget / jiraFile.getName)
        )
      )
    },
    Test / javaOptions    += cglibFix,
    Test / fork           := true,
    Test / test           := {
      val _ = copyPlugins.value
      (Test / test).value
    },
    Test / testOnly       := {
      val _ = copyPlugins.value
      (Test / testOnly).evaluated
    },
    Test / testQuick      := {
      val _ = copyPlugins.value
      (Test / testQuick).evaluated
    },
    Universal / mappings ++= {
      val esFile              = (elasticsearchPlugin / assembly).value
      val bgFile              = (blazegraphPlugin / assembly).value
      val graphAnalytics      = (graphAnalyticsPlugin / assembly).value
      val storageFile         = (storagePlugin / assembly).value
      val archiveFile         = (archivePlugin / assembly).value
      val compositeViewsFile  = (compositeViewsPlugin / assembly).value
      val searchFile          = (searchPlugin / assembly).value
      val projectDeletionFile = (projectDeletionPlugin / assembly).value
      val jiraFile            = (jiraPlugin / assembly).value
      Seq(
        (esFile, "plugins/" + esFile.getName),
        (bgFile, "plugins/" + bgFile.getName),
        (graphAnalytics, "plugins/" + graphAnalytics.getName),
        (storageFile, "plugins/" + storageFile.getName),
        (archiveFile, "plugins/" + archiveFile.getName),
        (compositeViewsFile, "plugins/" + compositeViewsFile.getName),
        (searchFile, "plugins/" + searchFile.getName),
        (jiraFile, "plugins/" + jiraFile.getName),
        (projectDeletionFile, "plugins/disabled/" + projectDeletionFile.getName)
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
    assembly / assemblyOption     := (assembly / assemblyOption).value.withIncludeScala(false),
    Test / fork                   := true
  )

lazy val elasticsearchPlugin = project
  .in(file("delta/plugins/elasticsearch"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk % "provided;test->test"
  )
  .settings(
    name                       := "delta-elasticsearch-plugin",
    moduleName                 := "delta-elasticsearch-plugin",
    assembly / assemblyJarName := "elasticsearch.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    libraryDependencies       ++= Seq(
      kamonAkkaHttp    % Provided,
      akkaTestKitTyped % Test,
      akkaSlf4j        % Test,
      logback          % Test,
      scalaTest        % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch",
    addCompilerPlugin(betterMonadicFor),
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-elasticsearch-plugin", "plugin"), assembly),
    Test / fork                := true
  )

lazy val blazegraphPlugin = project
  .in(file("delta/plugins/blazegraph"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk % "provided;test->test"
  )
  .settings(
    name                       := "delta-blazegraph-plugin",
    moduleName                 := "delta-blazegraph-plugin",
    libraryDependencies       ++= Seq(
      kamonAkkaHttp % Provided,
      akkaSlf4j     % Test,
      logback       % Test,
      scalaTest     % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.blazegraph",
    addCompilerPlugin(betterMonadicFor),
    assembly / assemblyJarName := "blazegraph.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-blazegraph-plugin", "plugin"), assembly),
    Test / fork                := true
  )

lazy val compositeViewsPlugin = project
  .in(file("delta/plugins/composite-views"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk                 % "provided;test->test",
    elasticsearchPlugin % "provided;test->test",
    blazegraphPlugin    % "provided;test->test"
  )
  .settings(
    name                       := "delta-composite-views-plugin",
    moduleName                 := "delta-composite-views-plugin",
    libraryDependencies       ++= Seq(
      alpakkaSse excludeAll (
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13"),
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-http_2.13")
      ),
      kamonAkkaHttp % Provided,
      akkaSlf4j     % Test,
      logback       % Test,
      scalaTest     % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.compositeviews",
    addCompilerPlugin(betterMonadicFor),
    coverageFailOnMinimum      := false, // TODO: Remove this line when coverage increases
    assembly / assemblyJarName := "composite-views.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-composite-views-plugin", "plugin"), assembly),
    Test / fork                := true
  )

lazy val searchPlugin = project
  .in(file("delta/plugins/search"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk                  % "provided;test->test",
    blazegraphPlugin     % "provided;test->compile;test->test",
    elasticsearchPlugin  % "provided;test->compile;test->test",
    compositeViewsPlugin % "provided;test->compile;test->test"
  )
  .settings(
    name                       := "delta-search-plugin",
    moduleName                 := "delta-search-plugin",
    libraryDependencies       ++= Seq(
      kamonAkkaHttp % Provided,
      akkaSlf4j     % Test,
      logback       % Test,
      scalaTest     % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.search",
    addCompilerPlugin(betterMonadicFor),
    coverageFailOnMinimum      := false, // TODO: Remove this line when coverage increases
    assembly / assemblyJarName := "search.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-search-plugin", "plugin"), assembly),
    Test / fork                := true
  )

lazy val storagePlugin = project
  .enablePlugins(BuildInfoPlugin)
  .in(file("delta/plugins/storage"))
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk                 % "provided;test->test",
    elasticsearchPlugin % "provided;test->compile;test->test",
    testkit             % "test->compile"
  )
  .settings(
    name                       := "delta-storage-plugin",
    moduleName                 := "delta-storage-plugin",
    libraryDependencies       ++= Seq(
      alpakkaS3 excludeAll (
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13"),
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-http_2.13"),
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-http-xml_2.13"),
        ExclusionRule(organization = "org.slf4j", name = "slf4j-api")
      ),
      kamonAkkaHttp    % Provided,
      akkaSlf4j        % Test,
      akkaTestKitTyped % Test,
      akkaHttpTestKit  % Test,
      logback          % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.storage",
    addCompilerPlugin(betterMonadicFor),
    addCompilerPlugin(kindProjector),
    coverageFailOnMinimum      := false, // TODO: Remove this line when coverage increases
    assembly / assemblyJarName := "storage.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-storage-plugin", "plugin"), assembly),
    Test / fork                := true
  )

lazy val archivePlugin = project
  .in(file("delta/plugins/archive"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk           % Provided,
    storagePlugin % "provided;test->test"
  )
  .settings(
    name                       := "delta-archive-plugin",
    moduleName                 := "delta-archive-plugin",
    libraryDependencies       ++= Seq(
      kamonAkkaHttp % Provided,
      alpakkaFile excludeAll (
        ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.13")
      ),
      akkaSlf4j     % Test,
      logback       % Test,
      scalaTest     % Test
    ),
    addCompilerPlugin(betterMonadicFor),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.archive",
    assembly / assemblyJarName := "archive.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-archive-plugin", "plugin"), assembly),
    Test / fork                := true
  )

lazy val projectDeletionPlugin = project
  .in(file("delta/plugins/project-deletion"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk % "provided;test->test"
  )
  .settings(
    name                       := "delta-project-deletion-plugin",
    moduleName                 := "delta-project-deletion-plugin",
    libraryDependencies       ++= Seq(
      kamonAkkaHttp % Provided,
      scalaTest     % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion",
    addCompilerPlugin(betterMonadicFor),
    assembly / assemblyJarName := "project-deletion.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-project-deletion-plugin", "plugin"), assembly),
    Test / fork                := true,
    coverageFailOnMinimum      := false
  )

lazy val graphAnalyticsPlugin = project
  .in(file("delta/plugins/graph-analytics"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk                 % Provided,
    storagePlugin       % "provided;test->test",
    elasticsearchPlugin % "provided;test->test"
  )
  .settings(
    name                       := "delta-graph-analytics-plugin",
    moduleName                 := "delta-graph-analytics-plugin",
    libraryDependencies       ++= Seq(
      kamonAkkaHttp % Provided,
      akkaSlf4j     % Test,
      logback       % Test,
      scalaTest     % Test
    ),
    addCompilerPlugin(betterMonadicFor),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics",
    assembly / assemblyJarName := "graph-analytics.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("delta-graph-analytics-plugin", "plugin"), assembly),
    Test / fork                := true,
    coverageFailOnMinimum      := false
  )

lazy val jiraPlugin = project
  .in(file("delta/plugins/jira"))
  .enablePlugins(BuildInfoPlugin)
  .settings(shared, compilation, assertJavaVersion, discardModuleInfoAssemblySettings, coverage, release)
  .dependsOn(
    sdk % "provided;test->test"
  )
  .settings(
    name                       := "delta-jira-plugin",
    moduleName                 := "delta-jira-plugin",
    libraryDependencies       ++= Seq(
      googleAuthClient,
      kamonAkkaHttp % Provided,
      scalaTest     % Test
    ),
    buildInfoKeys              := Seq[BuildInfoKey](version),
    buildInfoPackage           := "ch.epfl.bluebrain.nexus.delta.plugins.jira",
    addCompilerPlugin(betterMonadicFor),
    assembly / assemblyJarName := "jira.jar",
    assembly / assemblyOption  := (assembly / assemblyOption).value.withIncludeScala(false),
    assembly / test            := {},
    addArtifact(Artifact("jira", "plugin"), assembly),
    Test / fork                := true,
    coverageFailOnMinimum      := false
  )

lazy val plugins = project
  .in(file("delta/plugins"))
  .settings(shared, compilation, noPublish)
  .aggregate(
    elasticsearchPlugin,
    blazegraphPlugin,
    compositeViewsPlugin,
    searchPlugin,
    storagePlugin,
    archivePlugin,
    projectDeletionPlugin,
    testPlugin,
    graphAnalyticsPlugin,
    jiraPlugin
  )

lazy val delta = project
  .in(file("delta"))
  .settings(shared, compilation, noPublish)
  .aggregate(kernel, testkit, sourcingPsql, rdf, sdk, app, plugins)

lazy val cargo = taskKey[(File, String)]("Run Cargo to build 'nexus-fixer'")

lazy val storage = project
  .in(file("storage"))
  .enablePlugins(UniversalPlugin, UniversalDeployPlugin, JavaAppPackaging, JavaAgent, DockerPlugin, BuildInfoPlugin)
  .settings(
    shared,
    compilation,
    assertJavaVersion,
    kamonSettings,
    storageAssemblySettings,
    coverage,
    release,
    servicePackaging,
    addArtifact(Artifact("delta-storage-app", "application"), assembly),
    coverageMinimumStmtTotal := 75
  )
  .dependsOn(kernel, testkit % "test->compile")
  .settings(cargo := {
    import scala.sys.process._

    val log = streams.value.log
    val cmd = Process(Seq("cargo", "build", "--release"), baseDirectory.value / "permissions-fixer")
    if (cmd.! == 0) {
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
    libraryDependencies     ++= Seq(
      apacheCompress,
      akkaHttp,
      akkaHttpCirce,
      akkaStream,
      akkaSlf4j,
      alpakkaFile,
      catsCore,
      catsEffect,
      circeCore,
      circeGenericExtras,
      logback,
      pureconfig,
      akkaHttpTestKit % Test,
      akkaTestKit     % Test,
      mockito         % Test,
      munit           % Test,
      scalaTest       % Test
    ),
    cleanFiles              ++= Seq(
      baseDirectory.value / "permissions-fixer" / "target" / "**",
      baseDirectory.value / "nexus-storage.jar"
    ),
    Test / testOptions       += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", "target/test-reports"),
    Test / parallelExecution := false,
    Universal / mappings     := {
      (Universal / mappings).value :+ cargo.value
    }
  )

lazy val tests = project
  .in(file("tests"))
  .dependsOn(testkit)
  .settings(noPublish)
  .settings(shared, compilation, coverage, release)
  .settings(
    name                               := "tests",
    moduleName                         := "tests",
    coverageFailOnMinimum              := false,
    libraryDependencies               ++= Seq(
      akkaHttp,
      akkaStream,
      circeOptics,
      circeGenericExtras,
      fs2,
      logback,
      akkaTestKit     % Test,
      akkaHttpTestKit % Test,
      awsSdk          % Test,
      scalaTest       % Test,
      akkaSlf4j       % Test,
      alpakkaSse      % Test
    ),
    Test / parallelExecution           := false,
    Test / testOptions                 += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", "target/test-reports"),
    // Scalate gets errors with layering with this project so we disable it
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    Test / fork                        := true
  )

lazy val root = project
  .in(file("."))
  .settings(name := "nexus", moduleName := "nexus")
  .settings(compilation, shared, noPublish)
  .aggregate(docs, delta, storage, tests)

lazy val noPublish = Seq(
  publish / skip                         := true,
  Test / publish / skip                  := true,
  publishLocal                           := {},
  publish                                := {},
  publishArtifact                        := false,
  packageDoc / publishArtifact           := false,
  Compile / packageSrc / publishArtifact := false,
  Compile / packageDoc / publishArtifact := false,
  Test / packageBin / publishArtifact    := false,
  Test / packageDoc / publishArtifact    := false,
  Test / packageSrc / publishArtifact    := false,
  sonatypeCredentialHost                 := "s01.oss.sonatype.org",
  sonatypeRepository                     := "https://s01.oss.sonatype.org/service/local",
  versionScheme                          := Some("strict")
)

lazy val assertJavaVersion =
  Seq(
    checkJavaVersion  := {
      val current  = VersionNumber(sys.props("java.specification.version"))
      val required = VersionNumber(javaSpecificationVersion.value)
      assert(CompatibleJavaVersion(current, required), s"Java '$required' or above required; current '$current'")
    },
    Compile / compile := (Compile / compile).dependsOn(checkJavaVersion).value
  )

lazy val shared = Seq(
  organization      := "ch.epfl.bluebrain.nexus",
  // TODO: remove when https://github.com/djspiewak/sbt-github-packages/issues/28 is fixed
  githubTokenSource := TokenSource.Or(
    TokenSource.Environment("GITHUB_TOKEN"), // Injected during a github workflow for publishing
    TokenSource.Or(
      TokenSource.Environment("CI"),   // available in GH Actions
      TokenSource.Environment("SHELL") // safe to assume this will be set in all our devs environments
    )
  )
)

lazy val kamonSettings = Seq(
  libraryDependencies ++= Seq(
    kamonAkkaHttp,
    "io.kamon"        %% "kamon-akka"           % kamonVersion,
    "io.kamon"        %% "kamon-core"           % kamonVersion,
    "io.kamon"        %% "kamon-executors"      % kamonVersion,
    "io.kamon"        %% "kamon-jaeger"         % kamonVersion,
    "io.jaegertracing" % "jaeger-thrift"        % "1.6.0", // included jaeger-thrift causes a NoSuchMethod error
    "io.kamon"        %% "kamon-jdbc"           % kamonVersion,
    "io.kamon"        %% "kamon-logback"        % kamonVersion,
    "io.kamon"        %% "kamon-prometheus"     % kamonVersion,
    "io.kamon"        %% "kamon-scala-future"   % kamonVersion,
    "io.kamon"        %% "kamon-status-page"    % kamonVersion,
    "io.kamon"        %% "kamon-system-metrics" % kamonVersion
  ),
  javaAgents           += kanelaAgent
)

lazy val storageAssemblySettings = Seq(
  assembly / assemblyJarName       := "nexus-storage.jar",
  assembly / test                  := {},
  assembly / assemblyMergeStrategy := {
    case PathList("org", "apache", "commons", "logging", xs @ _*)        => MergeStrategy.last
    case PathList("org", "apache", "commons", "codec", xs @ _*)          => MergeStrategy.last
    case PathList("akka", "remote", "kamon", xs @ _*)                    => MergeStrategy.last
    case PathList("kamon", "instrumentation", "akka", "remote", xs @ _*) => MergeStrategy.last
    case PathList("javax", "annotation", xs @ _*)                        => MergeStrategy.first
    case x if x.endsWith("module-info.class")                            => MergeStrategy.discard
    case x                                                               =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
)

lazy val discardModuleInfoAssemblySettings = Seq(
  assembly / assemblyMergeStrategy := {
    case x if x.contains("io.netty.versions.properties") => MergeStrategy.discard
    case "module-info.class"                             => MergeStrategy.discard
    case x                                               =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
)

lazy val compilation = {
  import sbt.Keys._
  import sbt._

  Seq(
    scalaVersion                           := scalaCompilerVersion,
    scalacOptions                          ~= { options: Seq[String] => options.filterNot(Set("-Wself-implicit", "-Xlint:infer-any", "-Wnonunit-statement")) },
    javaSpecificationVersion               := "17",
    javacOptions                          ++= Seq(
      "-source",
      javaSpecificationVersion.value,
      "-target",
      javaSpecificationVersion.value,
      "-Xlint"
    ),
    excludeDependencies                   ++= Seq(
      ExclusionRule("log4j", "log4j"),
      ExclusionRule("org.apache.logging.log4j ", "log4j-api"),
      ExclusionRule("org.apache.logging.log4j ", "log4j-core")
    ),
    Compile / packageSrc / publishArtifact := !isSnapshot.value,
    Compile / packageDoc / publishArtifact := !isSnapshot.value,
    Compile / doc / scalacOptions         ++= Seq("-no-link-warnings"),
    Compile / doc / javacOptions           := Seq("-source", javaSpecificationVersion.value),
    autoAPIMappings                        := true,
    apiMappings                            += {
      val scalaDocUrl = s"http://scala-lang.org/api/${scalaVersion.value}/"
      ApiMappings.apiMappingFor((Compile / fullClasspath).value)("scala-library", scalaDocUrl)
    },
    Scapegoat / dependencyClasspath        := (Compile / dependencyClasspath).value
  )
}

lazy val coverage = Seq(
  coverageMinimumStmtTotal := 80,
  coverageFailOnMinimum    := true
)

lazy val release = Seq(
  Test / publish / skip               := true,
  Test / packageBin / publishArtifact := false,
  Test / packageDoc / publishArtifact := false,
  Test / packageSrc / publishArtifact := false,
  // removes compile time only dependencies from the resulting pom
  pomPostProcess                      := { node =>
    XmlTransformer.transformer(moduleFilter("org.scoverage") | moduleFilter("com.sksamuel.scapegoat")).transform(node).head
  },
  publishTo                           := {
    val original            = publishTo.value
    val ghTo                = githubPublishTo.value
    val RELEASE_TO_SONATYPE = sys.env.getOrElse("RELEASE_TO_SONATYPE", "false").toBoolean
    if (RELEASE_TO_SONATYPE) original else ghTo
  },
  sonatypeCredentialHost              := "s01.oss.sonatype.org",
  sonatypeRepository                  := "https://s01.oss.sonatype.org/service/local",
  versionScheme                       := Some("strict")
)

lazy val servicePackaging = {
  import com.typesafe.sbt.packager.Keys._
  import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{Docker, dockerChmodType}
  import com.typesafe.sbt.packager.docker.{DockerChmodType, DockerVersion}
  import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
  Seq(
    // Docker publishing settings
    Docker / maintainer   := "Nexus Team <noreply@epfl.ch>",
    Docker / version      := {
      if (isSnapshot.value) "latest"
      else version.value
    },
    Docker / daemonUser   := "nexus",
    dockerBaseImage       := "eclipse-temurin:17-jre",
    dockerBuildxPlatforms := Seq("linux/arm64/v8", "linux/amd64"),
    dockerExposedPorts    := Seq(8080),
    dockerUsername        := Some("bluebrain"),
    dockerUpdateLatest    := false,
    dockerChmodType       := DockerChmodType.UserGroupWriteExecute,
    dockerEnvVars         := Map(
      "JAVA_OPTS" -> cglibFix
    )
  )
}

ThisBuild / scapegoatVersion := scalacScapegoatVersion
ThisBuild / scapegoatDisabledInspections := Seq(
  "AsInstanceOf",
  "ClassNames",
  "IncorrectlyNamedExceptions",
  "ObjectNames",
  "RedundantFinalModifierOnCaseClass",
  "RedundantFinalModifierOnMethod",
  "VariableShadowing"
)
ThisBuild / homepage                     := Some(url("https://bluebrainnexus.io"))
ThisBuild / licenses                     := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scmInfo                      := Some(ScmInfo(url("https://github.com/BlueBrain/nexus"), "scm:git:git@github.com:BlueBrain/nexus.git"))
ThisBuild / developers                   := List(
  Developer("imsdu", "Simon Dumas", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
  Developer("olivergrabinski ", "Oliver Grabinski", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
  Developer("shinyhappydan", "Daniel Bell", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/"))
)
ThisBuild / githubOwner                  := "BlueBrain"
ThisBuild / githubRepository             := "nexus"
ThisBuild / sonatypeCredentialHost       := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository           := "https://s01.oss.sonatype.org/service/local"

Global / excludeLintKeys        += packageDoc / publishArtifact
Global / excludeLintKeys        += docs / paradoxRoots
Global / excludeLintKeys        += docs / Paradox / paradoxNavigationDepth
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

addCommandAlias(
  "review",
  s"""
     |;clean
     |;scalafmtCheck
     |;test:scalafmtCheck
     |;scalafmtSbtCheck
     |;coverage
     |;scapegoat
     |;test
     |;coverageReport
     |;coverageAggregate
     |""".stripMargin
)
addCommandAlias(
  "deltaReview",
  """
     |;delta/clean
     |;delta/scalafmtCheck
     |;delta/test:scalafmtCheck
     |;scalafmtSbtCheck;coverage
     |;delta/scapegoat
     |;delta/test
     |;delta/coverageReport
     |;delta/coverageAggregate
     |""".stripMargin
)
addCommandAlias("build-docs", ";docs/clean;docs/makeSite")
addCommandAlias("preview-docs", ";docs/clean;docs/previewSite")

val coreModules = List("kernel", "rdf", "sdk", "sourcingPsql", "testkit")

val staticAnalysis =
  s"""
    |project delta ;
    |scalafmtSbtCheck ;
    |scalafmtCheck ;
    |Test/scalafmtCheck ;
    |scapegoat ;
    |doc
    |""".stripMargin

addCommandAlias("static-analysis", staticAnalysis)

def unitTestsWithCoverageCommandsForModules(modules: List[String]) = {
  ";coverage" +
    modules.map(module => s";$module/test").mkString +
    modules.map(module => s";$module/coverageReport").mkString
}
addCommandAlias("core-unit-tests-with-coverage", unitTestsWithCoverageCommandsForModules(coreModules))
addCommandAlias("app-unit-tests-with-coverage", unitTestsWithCoverageCommandsForModules(List("app")))
addCommandAlias("plugins-unit-tests-with-coverage", unitTestsWithCoverageCommandsForModules(List("plugins")))

// This option allows distage 1.0.10 to run on JDK 17+
val cglibFix = "--add-opens=java.base/java.lang=ALL-UNNAMED"
