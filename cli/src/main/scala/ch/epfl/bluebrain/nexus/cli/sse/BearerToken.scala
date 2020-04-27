package ch.epfl.bluebrain.nexus.cli.sse

import pureconfig.ConfigConvert

/**
  * The value of the HTTP Header Authorization Bearer token.
  *
  * @param value the Bearer token value
  */
final case class BearerToken(value: String)

object BearerToken {
  implicit final val bearerTokenConvert: ConfigConvert[BearerToken] =
    ConfigConvert.viaString(str => Right(BearerToken(str)), _.value)
}
