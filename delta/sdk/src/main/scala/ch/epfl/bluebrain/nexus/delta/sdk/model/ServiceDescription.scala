package ch.epfl.bluebrain.nexus.delta.sdk.model

/**
  * Information about a service
  *
  * @param name    service name
  * @param version service version
  */
final case class ServiceDescription(name: String, version: String)
