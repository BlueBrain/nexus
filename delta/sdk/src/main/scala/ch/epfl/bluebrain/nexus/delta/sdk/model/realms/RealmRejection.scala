package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Realm rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class RealmRejection(val reason: String) extends Product with Serializable

object RealmRejection {

  /**
    * Enumeration of possible reasons why a realm is not found
    */
  sealed abstract class NotFound(reason: String) extends RealmRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a realm at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends NotFound(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when attempting to update a realm with an id that doesn't exist.
    *
    * @param label the label of the realm
    */
  final case class RealmNotFound(label: Label) extends NotFound(s"Realm '$label' not found.")

  /**
    * Rejection returned when attempting to create a realm with an id that already exists.
    *
    * @param label the label of the realm
    */
  final case class RealmAlreadyExists(label: Label) extends RealmRejection(s"Realm '$label' already exists.")

  /**
    * Rejection returned when attempting to create a realm with an openIdConfig that already exists.
    *
    * @param label        the label of the realm
    * @param openIdConfig the already existing openIdConfig
    */
  final case class RealmOpenIdConfigAlreadyExists(label: Label, openIdConfig: Uri)
      extends RealmRejection(s"Realm '$label' with openIdConfig '${openIdConfig.toString()}' already exists.")

  /**
    * Rejection returned when attempting to deprecate a realm that is already deprecated.
    *
    * @param label the label of the realm
    */
  final case class RealmAlreadyDeprecated(label: Label) extends RealmRejection(s"Realm '$label' is already deprecated.")

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
  final case class IllegalGrantTypeFormat(document: Uri, location: String)
      extends RealmRejection(
        s"Failed to parse '$location' from '${document.toString()}' as a collection of supported grant types."
      )

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the issuer is not properly
    * formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalIssuerFormat(document: Uri, location: String)
      extends RealmRejection(s"Failed to parse '$location' from '${document.toString()}' as an issuer url.")

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the jwks uri is not properly
    * formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalJwksUriFormat(document: Uri, location: String)
      extends RealmRejection(s"Failed to parse '$location' from '${document.toString()}' as a jwk url.")

  /**
    * Rejection returned when attempting to parse a JWKS document, but it's not properly formatted.
    *
    * @param document the address of the document
    */
  final case class IllegalJwkFormat(document: Uri)
      extends RealmRejection(s"Illegal format of the JWKs document '${document.toString()}'.")

  /**
    * Rejection returned when attempting to parse an openid configuration document, but the required endpoints were
    * not found or were not properly formatted.
    *
    * @param document the address of the document
    * @param location the location in the document
    */
  final case class IllegalEndpointFormat(document: Uri, location: String)
      extends RealmRejection(s"Failed to parse '$location' from '${document.toString()}' as a valid url.")

  /**
    * Rejection returned when attempting to fetch a JWKS document but the response is not a successful one.
    *
    * @param document the address of the document
    */
  final case class UnsuccessfulJwksResponse(document: Uri)
      extends RealmRejection(s"Failed to retrieve the JWKs document '${document.toString()}'.")

  /**
    * Rejection returned when attempting to fetch an openid config document but the response is not a successful one.
    *
    * @param document the address of the document
    */
  final case class UnsuccessfulOpenIdConfigResponse(document: Uri)
      extends RealmRejection(s"Failed to retrieve the openid config document '${document.toString()}'.")

  /**
    * Rejection returned when attempting to parse a JWKS document, but no supported keys are found.
    *
    * @param document the address of the document
    */
  final case class NoValidKeysFound(document: Uri)
      extends RealmRejection(s"Failed to find a valid RSA JWK key at '${document.toString()}'.")

  /**
    * Rejection returned when the returned state is the initial state after a Realm.evaluation plus a Realm.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(label: Label)
      extends RealmRejection(s"Unexpected initial state for realm '$label'.")

  implicit private val realmRejectionEncoder: Encoder.AsObject[RealmRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case IncorrectRev(provided, expected) =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                => default
      }
    }

  implicit final val realmRejectionJsonLdEncoder: JsonLdEncoder[RealmRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

}
