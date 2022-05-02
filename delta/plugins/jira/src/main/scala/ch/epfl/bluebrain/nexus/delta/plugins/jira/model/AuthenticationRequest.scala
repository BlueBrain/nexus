package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import scala.annotation.nowarn

/**
  * The request sent back to the user so that he can authorize the client
  * @param value
  *   the Jira uri so that the user can approve
  */
final case class AuthenticationRequest(value: Uri)

object AuthenticationRequest {
  @nowarn("cat=unused")
  implicit private val configuration: Configuration                         = Configuration.default.withStrictDecoding
  implicit val authenticationRequestDecoder: Encoder[AuthenticationRequest] =
    deriveConfiguredEncoder[AuthenticationRequest]
}
