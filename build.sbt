import com.typesafe.sbt.packager.MappingsHelper

lazy val root = project
  .in(file("."))
  .enablePlugins(ParadoxPlugin, UniversalPlugin)
  .settings(
    name                           := "docs",
    moduleName                     := "docs",
    paradoxTheme                   := Some(builtinParadoxTheme("generic")),
    paradoxProperties in Compile   ++= Map("extref.service.base_url" -> "../%s"),
    topLevelDirectory in Universal := None,
    packageName in Universal       := name.value,
    mappings in Universal          := MappingsHelper.contentOf((paradox in Compile).value)
  )

inThisBuild(
  List(
    homepage := Some(url("https://github.com/BlueBrain/nexus")),
    licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scmInfo  := Some(ScmInfo(url("https://github.com/BlueBrain/nexus"), "scm:git:git@github.com:BlueBrain/nexus.git")),
    developers := List(
      Developer("bogdanromanx", "Bogdan Roman", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("hygt", "Henry Genet", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("umbreak", "Didac Montero Mendez", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("wwajerowicz", "Wojtek Wajerowicz", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/"))
    ),
    publishLocal    := {},
    publish         := {},
    publishArtifact := false
  )
)

addCommandAlias("review", ";clean;paradox")
