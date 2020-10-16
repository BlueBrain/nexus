package ch.epfl.bluebrain.nexus.delta.config

/**
  * The service description.
  * @param name the name of the service
  */
final case class DescriptionConfig(
    name: String
) {

  /**
    * @return the version of the service
    */
  val version: String = BuildInfo.version

  /**
    * @return the full name of the service (name + version)
    */
  val fullName: String = s"$name-${version.replaceAll("\\W", "-")}"
}
