lazy val root = project
  .in(file("."))
  .enablePlugins(DocsPackagingPlugin)
  .settings(publishSettings)
  .settings(
    name                         := "docs",
    moduleName                   := "docs",
    paradoxTheme                 := Some(builtinParadoxTheme("generic")),
    paradoxProperties in Compile ++= Map("extref.service.base_url" -> "../%s"),
    packageName in Docker        := "docs"
  )

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/BlueBrain/nexus")),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  scmInfo  := Some(ScmInfo(url("https://github.com/BlueBrain/nexus"), "scm:git:git@github.com:BlueBrain/nexus.git"))
)

addCommandAlias("review", ";clean;paradox")
addCommandAlias("rel", ";release with-defaults skip-tests")
