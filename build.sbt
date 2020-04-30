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

val javaSpecificationVersion = "11"
val scalacSilencerVersion    = "1.4.4"

val catsVersion          = "2.1.1"
val catsEffectVersion    = "2.1.3"
val catsRetryVersion     = "0.3.2"
val circeVersion         = "0.13.0"
val distageVersion       = "0.10.6"
val declineVersion       = "1.2.0"
val doobieVersion        = "0.9.0"
val fs2Version           = "2.3.0"
val http4sVersion        = "0.21.3"
val jenaVersion          = "3.14.0"
val kindProjectorVersion = "0.11.0"
val log4catsVersion      = "1.0.1"
val logbackVersion       = "1.2.3"
val magnoliaVersion      = "0.12.8"
val monixVersion         = "3.1.0"
val parboiledVersion     = "2.2.0"
val pureconfigVersion    = "0.12.3"
val scalaTestVersion     = "3.1.1"

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
lazy val http4sDsl       = "org.http4s"            %% "http4s-dsl"                % http4sVersion
lazy val jenaArq         = "org.apache.jena"       % "jena-arq"                   % jenaVersion
lazy val kindProjector   = "org.typelevel"         %% "kind-projector"            % kindProjectorVersion
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
  .enablePlugins(ParadoxPlugin, ParadoxMaterialThemePlugin, ParadoxSitePlugin, GhpagesPlugin)
  .disablePlugins(ScapegoatSbtPlugin)
  .settings(shared, compilation)
  .settings(ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox))
  .settings(
    name       := "docs",
    moduleName := "docs",
    // paradox settings
    sourceDirectory in Paradox := sourceDirectory.value / "main" / "paradox",
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
  .settings(shared, compilation, coverage, release)
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

lazy val cli = project
  .in(file("cli"))
  .enablePlugins(UniversalPlugin, JavaAppPackaging, DockerPlugin)
  .settings(shared, compilation, coverage, release, servicePackaging)
  .settings(
    name                 := "cli",
    moduleName           := "cli",
    Docker / packageName := "nexus-cli",
    coverageMinimum      := 50d,
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
      distageCore,
      http4sCirce,
      http4sClient,
      fs2,
      log4catsSlf4j,
      monixEval,
      pureconfig,
      circeLiteral   % Test,
      distageDocker  % Test,
      distageTestkit % Test,
      http4sDsl      % Test,
      jenaArq        % Test,
      scalaTest      % Test
    )
  )

lazy val root = project
  .in(file("."))
  .settings(name := "nexus", moduleName := "nexus")
  .settings(noPublish)
  .aggregate(docs, cli, rdf)

lazy val noPublish = Seq(publishLocal := {}, publish := {}, publishArtifact := false)

lazy val shared = Seq(
  organization := "ch.epfl.bluebrain.nexus",
  resolvers ++= Seq(
    Resolver.bintrayRepo("bbp", "nexus-releases"),
    Resolver.bintrayRepo("bbp", "nexus-snapshots")
  )
)

lazy val compilation = Seq(
  scalaVersion := "2.13.1", // scapegoat plugin not published yet for 2.13.2
  // to be removed when migrating to 2.13.2 and replaced with @nowarn (scapegoat plugin not published yet for 2.13.2)
  libraryDependencies ++= Seq(
    compilerPlugin("com.github.ghik" % "silencer-plugin" % scalacSilencerVersion cross CrossVersion.full),
    "com.github.ghik" % "silencer-lib" % scalacSilencerVersion % Provided cross CrossVersion.full
  ),
  scalacOptions ~= { options: Seq[String] => options.filterNot(Set("-Wself-implicit")) },
  javacOptions ++= Seq(
    "-source",
    javaSpecificationVersion,
    "-target",
    javaSpecificationVersion,
    "-Xlint"
  ),
  scalacOptions in (Compile, doc) ++= Seq("-no-link-warnings"),
  javacOptions in (Compile, doc)  := Seq("-source", javaSpecificationVersion),
  autoAPIMappings                 := true,
  apiMappings += {
    val scalaDocUrl = s"http://scala-lang.org/api/${scalaVersion.value}/"
    ApiMappings.apiMappingFor((fullClasspath in Compile).value)("scala-library", scalaDocUrl)
  },
  // fail the build initialization if the JDK currently used is not ${javaSpecificationVersion} or higher
  initialize := {
    // runs the previous initialization
    initialize.value
    // runs the java compatibility check
    val current  = VersionNumber(sys.props("java.specification.version"))
    val required = VersionNumber(javaSpecificationVersion)
    assert(CompatibleJavaVersion(current, required), s"Java '$required' or above required; current '$current'")
  }
)

lazy val coverage = Seq(
  coverageMinimum       := 80,
  coverageFailOnMinimum := true
)

lazy val release = Seq(
  bintrayOrganization := Some("bbp"),
  bintrayRepository := {
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
  pomPostProcess := { node =>
    XmlTransformer.transformer(moduleFilter("org.scoverage") | moduleFilter("com.sksamuel.scapegoat")).transform(node).head
  }
)

lazy val servicePackaging = {
  import com.typesafe.sbt.packager.Keys._
  import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{dockerChmodType, Docker}
  import com.typesafe.sbt.packager.docker.{DockerChmodType, DockerVersion}
  import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
  Seq(
    // package the kanela agent as a fixed name jar
    mappings in Universal := {
      val universalMappings = (mappings in Universal).value
      universalMappings.foldLeft(Vector.empty[(File, String)]) {
        case (acc, (file, filename)) if filename.contains("kanela-agent") =>
          acc :+ (file -> "lib/instrumentation-agent.jar")
        case (acc, other) =>
          acc :+ other
      } :+ (WaitForIt.download(target.value) -> "bin/wait-for-it.sh")
    },
    // docker publishing settings
    Docker / maintainer := "Nexus Team <noreply@epfl.ch>",
    Docker / version    := "latest",
    Docker / daemonUser := "nexus",
    dockerBaseImage     := "adoptopenjdk:11-jre-hotspot",
    dockerExposedPorts  := Seq(8080, 2552),
    dockerUsername      := Some("bluebrain"),
    dockerUpdateLatest  := false,
    dockerChmodType     := DockerChmodType.UserGroupWriteExecute,
    dockerVersion := Some(
      DockerVersion(19, 3, 5, Some("ce"))
    ) // forces the version because gh-actions version is 3.0.x which is not recognized to support multistage
  )
}

inThisBuild(
  Seq(
    scapegoatVersion     := "1.4.3",
    scapegoatMaxWarnings := 0,
    scapegoatMaxErrors   := 0,
    scapegoatMaxInfos    := 0,
    scapegoatDisabledInspections := Seq(
      "RedundantFinalModifierOnCaseClass",
      "RedundantFinalModifierOnMethod",
      "ObjectNames",
      "AsInstanceOf",
      "ClassNames",
      "VariableShadowing"
    ),
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

addCommandAlias("review", ";clean;scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;coverage;scapegoat;test;coverageReport;coverageAggregate")
addCommandAlias("build-docs", ";docs/clean;docs/makeSite")
