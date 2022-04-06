package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import scala.annotation.nowarn

final case class Verifier(value: String)

object Verifier {
  @nowarn("cat=unused")
  implicit private val configuration: Configuration       = Configuration.default.withStrictDecoding
  implicit val verificationCodeDecoder: Decoder[Verifier] = deriveConfiguredDecoder[Verifier]
}
