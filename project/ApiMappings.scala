import sbt._

object ApiMappings {

  /**
    * <p>Constructs a tuple (java.io.File -> sbt.URL) by searching the ''classpath'' for the jar file with the argument
    * ''artifactId''.  The value returned can be appended to the ''apiMappings'' such that documentation linking works
    * when building the docs.</p>
    *
    * <p>For example:
    * {{{
    *   lazy val myProject = project.settings(Seq(
    *     apiMappings += {
    *       val scalaDocUrl = "http://scala-lang.org/api/" + scalaVersion.value + "/"
    *       apiMappingFor((fullClasspath in Compile).value)("scala-library", scalaDocUrl)
    *     }
    *   ))
    * }}}
    * </p>
    *
    * @param classpath  the classpath to search
    * @param artifactId the artifact that provides the linked type
    * @param address    the full URL (as string) of the artifact documentation
    * @return a tuple (java.io.File -> sbt.URL) to be appended to the ''apiMappings''
    */
  final def apiMappingFor(classpath: Seq[Attributed[File]])(artifactId: String, address: String): (File, URL) = {
    @SuppressWarnings(Array("OptionGet"))
    def findJar(nameBeginsWith: String): File = {
      classpath
        .find { attributed: Attributed[java.io.File] => (attributed.data ** s"$nameBeginsWith*.jar").get.nonEmpty }
        .get
        .data // fail hard if not found
    }
    findJar(artifactId) -> url(address)
  }
}
