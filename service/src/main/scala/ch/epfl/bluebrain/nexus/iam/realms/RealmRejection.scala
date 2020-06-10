package ch.epfl.bluebrain.nexus.iam.realms

import akka.http.scaladsl.model.StatusCodes.{BadRequest, Conflict}
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.iam.types.Label
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts.errorCtxUri
import ch.epfl.bluebrain.nexus.service.routes.ResourceRejection
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

/**
  * Enumeration of realm rejection types.
  *
  * @param msg a descriptive message for why the rejection occurred
  */
sealed abstract class RealmRejection(val msg: String) extends ResourceRejection

object RealmRejection {

  /**
    * Rejection returned when attempting to create a realm with an id that already exists.
    *
    * @param label the label of the realm
    */
  final case class RealmAlreadyExists(label: Label) extends RealmRejection(s"Realm '${label.value}' already exists.")

  /**
    * Rejection returned when attempting to create a realm with an openIdConfig that already exists.
    *
    * @param label        the label of the realm
    * @param openIdConfig the already existing openIdConfig
    */
  final case class RealmOpenIdConfigAlreadyExists(label: Label, openIdConfig: Url)
      extends RealmRejection(s"Realm '${label.value}' with openIdConfig '${openIdConfig.asString}' already exists.")

  /**
    * Rejection returned when attempting to update a realm with an id that doesnt exist.
    *
    * @param label the label of the realm
    */
  final case class RealmNotFound(label: Label) extends RealmRejection(s"Realm '${label.value}' not found.")

  /**
    * Rejection returned when attempting to deprecate a realm that is already deprecated.
    *
    * @param label the label of the realm
    */
  final case class RealmAlreadyDeprecated(label: Label)
      extends RealmRejection(s"Realm '${label.value}' is already deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current realm, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends RealmRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the realm may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the grant types are not properly
    * formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalGrantTypeFormat(document: Url, location: String)
      extends RealmRejection(
        s"Failed to parse '$location' from '${document.asUri}' as a collection of supported grant types."
      )

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the issuer is not properly
    * formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalIssuerFormat(document: Url, location: String)
      extends RealmRejection(s"Failed to parse '$location' from '${document.asUri}' as an issuer url.")

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the jwks uri is not properly
    * formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalJwksUriFormat(document: Url, location: String)
      extends RealmRejection(s"Failed to parse '$location' from '${document.asUri}' as a jwk url.")

  /**
    * Rejection returned when attempting to parse a JWKS document, but it's not properly formatted.
    *
    * @param document the address of the document
    */
  final case class IllegalJwkFormat(document: Url)
      extends RealmRejection(s"Illegal format of the JWKs document '${document.asUri}'.")

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the required endpoints were
    * not found or were not properly formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalEndpointFormat(document: Url, location: String)
      extends RealmRejection(s"Failed to parse '$location' from '${document.asUri}' as a valid url.")

  /**
    * Rejection returned when attempting to fetch a JWKS document but the response is not a successful one.
    *
    * @param document the address of the document
    */
  final case class UnsuccessfulJwksResponse(document: Url)
      extends RealmRejection(s"Failed to retrieve the JWKs document '${document.asUri}'.")

  /**
    * Rejection returned when attempting to fetch an openid config document but the response is not a successful one.
    *
    * @param document the address of the document
    */
  final case class UnsuccessfulOpenIdConfigResponse(document: Url)
      extends RealmRejection(s"Failed to retrieve the openid config document '${document.asUri}'.")

  /**
    * Rejection returned when attempting to parse a JWKS document, but no supported keys are found.
    *
    * @param document the address of the document
    */
  final case class NoValidKeysFound(document: Url)
      extends RealmRejection(s"Failed to find a valid RSA JWK key at '${document.asUri}'.")

  implicit private[RealmRejection] val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")

  implicit val realmRejectionEncoder: Encoder[RealmRejection] = {
    val enc = deriveConfiguredEncoder[RealmRejection].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val realmRejectionStatusFrom: StatusFrom[RealmRejection] =
    StatusFrom {
      case _: RealmAlreadyExists               => BadRequest
      case _: RealmOpenIdConfigAlreadyExists   => BadRequest
      case _: RealmNotFound                    => BadRequest
      case _: RealmAlreadyDeprecated           => BadRequest
      case _: IncorrectRev                     => Conflict
      case _: IllegalGrantTypeFormat           => BadRequest
      case _: IllegalIssuerFormat              => BadRequest
      case _: IllegalJwksUriFormat             => BadRequest
      case _: IllegalEndpointFormat            => BadRequest
      case _: IllegalJwkFormat                 => BadRequest
      case _: UnsuccessfulJwksResponse         => BadRequest
      case _: UnsuccessfulOpenIdConfigResponse => BadRequest
      case _: NoValidKeysFound                 => BadRequest
    }
}
