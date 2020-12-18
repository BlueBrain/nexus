package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

/**
  * Enumeration of identity resolutions for a resolver
  */
sealed trait IdentityResolution

object IdentityResolution {

  /**
    * The resolution will use the identities of the caller at the moment of the resolution
    */
  final case object UseCurrentCaller extends IdentityResolution

  /**
    * The resolution will rely on the provided entities
    * @param value the identities
    */
  final case class ProvidedIdentities(value: Set[Identity]) extends IdentityResolution

  implicit def identityResolutionEncoder(implicit
      identityEncoder: Encoder[Identity]
  ): Encoder.AsObject[IdentityResolution] = {
    Encoder.AsObject.instance {
      case UseCurrentCaller          => JsonObject.singleton("useCurrentCaller", Json.fromBoolean(true))
      case ProvidedIdentities(value) =>
        JsonObject.singleton("identities", value.asJson)
    }
  }
}
