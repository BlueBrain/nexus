package ch.epfl.bluebrain.nexus.cli.types

/**
  * The value of the HTTP Header Authorization Bearer token.
  *
  * @param value the Bearer token value
  */
final case class BearerToken(value: String)
