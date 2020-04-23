/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
  align = most
  align.tokens.add = [
    { code = ":=", owner = "Term.ApplyInfix" }
    { code = "+=", owner = "Term.ApplyInfix" }
    { code = "++=", owner = "Term.ApplyInfix" }
  ]
}
 */

val catsVersion       = "2.1.1"
val catsEffectVersion = "2.1.2"
val catsRetryVersion  = "0.3.2"
val circeVersion      = "0.13.0"
val distageVersion    = "0.10.2-M8"
val declineVersion    = "1.0.0"
val doobieVersion     = "0.8.8"
val fs2Version        = "2.2.2"
val http4sVersion     = "0.21.1"
val jenaVersion       = "3.14.0"
val log4catsVersion   = "1.0.1"
val logbackVersion    = "1.2.3"
val magnoliaVersion   = "0.12.8"
val monixVersion      = "3.1.0"
val parboiledVersion  = "2.2.0"
val pureconfigVersion = "0.12.3"
val scalaTestVersion  = "3.1.1"

lazy val alleycatsCore   = "org.typelevel"         %% "alleycats-core"            % catsVersion
lazy val catsCore        = "org.typelevel"         %% "cats-core"                 % catsVersion
lazy val catsEffect      = "org.typelevel"         %% "cats-effect"               % catsEffectVersion
lazy val catsRetry       = "com.github.cb372"      %% "cats-retry-core"           % catsRetryVersion
lazy val catsEffectRetry = "com.github.cb372"      %% "cats-retry-cats-effect"    % catsRetryVersion
lazy val circeCore       = "io.circe"              %% "circe-core"                % circeVersion
lazy val circeGeneric    = "io.circe"              %% "circe-generic"             % circeVersion
lazy val circeLiteral    = "io.circe"              %% "circe-literal"             % circeVersion
lazy val circeParser     = "io.circe"              %% "circe-parser"              % circeVersion
lazy val distageCore     = "io.7mind.izumi"        %% "distage-core"              % distageVersion
lazy val distageDocker   = "io.7mind.izumi"        %% "distage-framework-docker"  % distageVersion
lazy val distageTestkit  = "io.7mind.izumi"        %% "distage-testkit-scalatest" % distageVersion
lazy val decline         = "com.monovore"          %% "decline"                   % declineVersion
lazy val doobiePostgres  = "org.tpolecat"          %% "doobie-postgres"           % doobieVersion
lazy val fs2             = "co.fs2"                %% "fs2-core"                  % fs2Version
lazy val http4sCirce     = "org.http4s"            %% "http4s-circe"              % http4sVersion
lazy val http4sClient    = "org.http4s"            %% "http4s-blaze-client"       % http4sVersion
lazy val jenaArq         = "org.apache.jena"       % "jena-arq"                   % jenaVersion
lazy val log4cats        = "io.chrisdavenport"     %% "log4cats-core"             % log4catsVersion
lazy val log4catsSlf4j   = "io.chrisdavenport"     %% "log4cats-slf4j"            % log4catsVersion
lazy val logback         = "ch.qos.logback"        % "logback-classic"            % logbackVersion
lazy val magnolia        = "com.propensive"        %% "magnolia"                  % magnoliaVersion
lazy val monixEval       = "io.monix"              %% "monix-eval"                % monixVersion
lazy val parboiled2      = "org.parboiled"         %% "parboiled"                 % parboiledVersion
lazy val pureconfig      = "com.github.pureconfig" %% "pureconfig"                % pureconfigVersion
lazy val scalaTest       = "org.scalatest"         %% "scalatest"                 % scalaTestVersion

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(ParadoxMaterialThemePlugin, ParadoxSitePlugin, GhpagesPlugin)
  .settings(
    name       := "docs",
    moduleName := "docs",
    // paradox settings
    sourceDirectory in Paradox := sourceDirectory.value / "main" / "paradox",
    ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox),
    paradoxMaterialTheme in Paradox := {
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
    // gh pages settings
    git.remoteRepo  := "git@github.com:BlueBrain/nexus.git",
    ghpagesNoJekyll := true,
    ghpagesBranch   := "gh-pages"
  )

lazy val rdf = project
  .in(file("rdf"))
  .settings(name := "rdf", moduleName := "rdf")
  .settings(noPublish)
  .settings(
    libraryDependencies ++= Seq(
      alleycatsCore,
      catsCore,
      magnolia,
      parboiled2,
      circeCore,
      circeParser,
      circeLiteral % Test,
      jenaArq      % Test,
      scalaTest    % Test
    )
  )

lazy val cliShared = project
  .in(file("cli/shared"))
  .settings(noPublish)
  .settings(
    name            := "cli-shared",
    moduleName      := "cli-shared",
    coverageMinimum := 70d,
    libraryDependencies ++= Seq(
      catsCore,
      catsEffect,
      catsEffectRetry,
      circeGeneric,
      circeParser,
      distageCore,
      http4sCirce,
      http4sClient,
      jenaArq,
      fs2,
      log4catsSlf4j,
      pureconfig,
      circeLiteral % Test,
      scalaTest    % Test
    )
  )

lazy val influxdb = project
  .in(file("cli/influxdb"))
  .dependsOn(cliShared)
  .settings(name := "influxdb", moduleName := "influxdb")
  .settings(libraryDependencies ++= Seq(monixEval, scalaTest % Test))

lazy val postgres = project
  .in(file("cli/postgres"))
  .dependsOn(cliShared % "compile->compile;test->test")
  .settings(
    name            := "postgres",
    moduleName      := "postgres",
    coverageMinimum := 50d,
    libraryDependencies ++= Seq(
      catsRetry,
      catsEffectRetry,
      distageCore,
      decline,
      doobiePostgres,
      monixEval,
      distageDocker  % Test,
      distageTestkit % Test,
      scalaTest      % Test
    )
  )

lazy val cli = project
  .in(file("cli"))
  .settings(
    name       := "cli",
    moduleName := "cli",
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
      distageCore,
      http4sCirce,
      http4sClient,
      jenaArq,
      fs2,
      log4catsSlf4j,
      monixEval,
      pureconfig,
      circeLiteral   % Test,
      distageDocker  % Test,
      distageTestkit % Test,
      scalaTest      % Test
    )
  )
//  .aggregate(cliShared, influxdb, postgres)

lazy val root = project
  .in(file("."))
  .settings(name := "nexus", moduleName := "nexus")
  .settings(noPublish)
  .aggregate(docs, cli, rdf)

lazy val noPublish = Seq(publishLocal := {}, publish := {}, publishArtifact := false)

inThisBuild(
  Seq(
    homepage := Some(url("https://github.com/BlueBrain/nexus-commons")),
    licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scmInfo  := Some(ScmInfo(url("https://github.com/BlueBrain/nexus-commons"), "scm:git:git@github.com:BlueBrain/nexus-commons.git")),
    developers := List(
      Developer("bogdanromanx", "Bogdan Roman", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("umbreak", "Didac Montero Mendez", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("wwajerowicz", "Wojtek Wajerowicz", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/"))
    ),
    // These are the sbt-release-early settings to configure
    releaseEarlyWith              := BintrayPublisher,
    releaseEarlyNoGpg             := true,
    releaseEarlyEnableSyncToMaven := false
  )
)

addCommandAlias("review", ";clean;scalafmtSbt;test:scalafmtCheck;scalafmtSbtCheck;coverage;scapegoat;test;coverageReport;coverageAggregate")
addCommandAlias("build-docs", ";docs/clean;docs/makeSite")
