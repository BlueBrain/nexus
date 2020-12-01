package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json, JsonObject}
import io.circe.syntax._

import scala.annotation.nowarn

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

  @nowarn("cat=unused")
  implicit val identityResolutionEncoder: Encoder.AsObject[IdentityResolution] = {
    implicit val config: Configuration = Configuration.default.withDiscriminator(keywords.tpe)
    Encoder.AsObject.instance {
      case UseCurrentCaller          => JsonObject.singleton("useCurrentCaller", Json.fromBoolean(true))
      case ProvidedIdentities(value) =>
        implicit val identityEncoder: Encoder.AsObject[Identity] = deriveConfiguredEncoder[Identity]
        JsonObject.singleton("identities", value.asJson)
    }
  }
}
