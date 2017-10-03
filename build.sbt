lazy val root = project.in(file("."))
  .enablePlugins(DocsPackagingPlugin)
  .settings(
    name                          := "docs",
    moduleName                    := "docs",
    paradoxTheme                  := Some(builtinParadoxTheme("generic")),
    paradoxProperties in Compile ++= Map("extref.service.base_url" -> "../%s"),
    packageName in Docker         := "docs")

addCommandAlias("review", ";clean;paradox")
addCommandAlias("rel",    ";release with-defaults skip-tests")
