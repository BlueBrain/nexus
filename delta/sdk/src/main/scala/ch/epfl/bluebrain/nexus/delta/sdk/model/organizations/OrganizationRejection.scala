package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import monix.bio.{IO, UIO}

/**
  * Enumeration of organization rejection types.
  *
 * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class OrganizationRejection(val reason: String) extends Product with Serializable

object OrganizationRejection {

  /**
    * Signals the the organization already exists.
    */
  final case class OrganizationAlreadyExists(label: Label)
      extends OrganizationRejection(s"Organization with label '$label' already exists.")

  /**
    * Signals that the organization does not exist.
    */
  final case class OrganizationNotFound private (override val reason: String) extends OrganizationRejection(reason)

  object OrganizationNotFound {
    def apply(uuid: UUID): OrganizationNotFound =
      new OrganizationNotFound(s"Organization with uuid '${uuid.toString.toLowerCase()}' not found.")

    def apply(label: Label): OrganizationNotFound =
      new OrganizationNotFound(s"Organization with label '$label' not found.")
  }

  /**
    * Signals that the provided revision does not match the latest revision
    *
    * @param expected latest know revision
    * @param provided provided revision
    */
  final case class IncorrectRev(expected: Long, provided: Long)
      extends OrganizationRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the organization may have been updated since last seen."
      )

  /**
    * Signals an attempt to retrieve an organization at a specific revision when the provided revision does not exist.
    *
   * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends OrganizationRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Signals and attempt to update/deprecate an organization that is already deprecated.
    *
   * @param label the label of the organization
    */
  final case class OrganizationIsDeprecated(label: Label)
      extends OrganizationRejection(s"Organization '$label' is deprecated.")

  implicit final val orgRejectionJsonLdEncoder: JsonLdEncoder[OrganizationRejection] =
    new JsonLdEncoder[OrganizationRejection] {
      private val bnode = BNode.random

      implicit private val orgRejectionEncoder: Encoder.AsObject[OrganizationRejection] =
        Encoder.AsObject.instance { r =>
          val tpe = r.getClass.getSimpleName.split('$').head
          JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
        }

      override def apply(value: OrganizationRejection): IO[RdfError, JsonLd] =
        JsonLd.compactedUnsafe(value.asJsonObject, defaultContext, bnode).pure[UIO]

      override val defaultContext: RawJsonLdContext = RawJsonLdContext(contexts.error.asJson)
    }

}
