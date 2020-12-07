package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{InvalidId, UnexpectedId}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Storage rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class StorageRejection(val reason: String) extends Product with Serializable

object StorageRejection {

  /**
    * Rejection returned when a subject intends to retrieve a storage at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends StorageRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a storage at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: Label) extends StorageRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a storage with an id that already exists.
    *
    * @param id      the storage identifier
    * @param project the project it belongs to
    */
  final case class StorageAlreadyExists(id: Iri, project: ProjectRef)
      extends StorageRejection(s"Storage '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update/fetch a storage with an id that doesn't exist.
    *
    * @param id      the storage identifier
    * @param project the project it belongs to
    */
  final case class StorageNotFound(id: Iri, project: ProjectRef)
      extends StorageRejection(s"Storage '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to fetch the default storage for a project but there is none.
    *
    * @param project the project it belongs to
    */
  final case class DefaultStorageNotFound(project: ProjectRef)
      extends StorageRejection(s"Default storage not found in project '$project'.")

  /**
    * Rejection returned when attempting to create/update a storage but it cannot be accessed.
    *
    * @param id      the storage identifier
    * @param project the project it belongs to
    */
  final case class StorageNotAccessible(id: Iri, details: String)
      extends StorageRejection(s"Storage '$id' not accessible.")

  /**
    * Rejection returned when attempting to create a storage where the passed id does not match the id on the payload.
    *
    * @param id        the storage identifier
    * @param payloadId the storage identifier on the payload
    */
  final case class UnexpectedStorageId(id: Iri, payloadId: Iri)
      extends StorageRejection(s"Storage '$id' does not match storage id on payload '$payloadId'.")

  /**
    * Rejection returned when attempting to interact with a storage providing an id that cannot be resolved to an Iri.
    *
    * @param id  the storage identifier
    */
  final case class InvalidStorageId(id: String)
      extends StorageRejection(s"Storage identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection when attempting to decode an expanded JsonLD as a case class
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends StorageRejection(error.getMessage)

  /**
    * Signals an error creating/updating a storage with a wrong maxFileSize
    */
  final case class InvalidMaxFileSize(id: Iri, value: Long, maxAllowed: Long)
      extends StorageRejection(
        s"'maxFileSize' field on storage '$id' has wrong range. Found '$value'. Allowed range [1,$maxAllowed]."
      )

  /**
    * Signals an error converting the source Json to JsonLD
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends StorageRejection(s"Storage ${id.fold("")(id => s"'$id'")} has invalid JSON-LD payload.")

  /**
    * Rejection returned when attempting to create a storage with an id that already exists.
    *
    * @param id the storage identifier
    */
  final case class DifferentStorageType(id: Iri, found: StorageType, expected: StorageType)
      extends StorageRejection(s"Storage '$id' is of type '$found' and can't be updated to be a '$expected' .")

  /**
    * Rejection returned when a subject intends to perform an operation on the current storage, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends StorageRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the storage may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to update/deprecate a storage that is already deprecated.
    *
    * @param id the storage identifier
    */
  final case class StorageIsDeprecated(id: Iri) extends StorageRejection(s"Storage '$id' is deprecated.")

  /**
    * Rejection returned when the associated project is invalid
    *
    * @param rejection the rejection which occurred with the project
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends StorageRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends StorageRejection(rejection.reason)

  /**
    * Rejection returned when the returned state is the initial state after a Storages.evaluation plus a Storages.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends StorageRejection(s"Unexpected initial state for storage '$id' of project '$project'.")

  implicit val storageJsonLdRejectionMapper: Mapper[JsonLdRejection, StorageRejection] = {
    case InvalidId(id)                                     => InvalidStorageId(id)
    case UnexpectedId(id, payloadIri)                      => UnexpectedStorageId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit val storageProjectRejectionMapper: Mapper[ProjectRejection, StorageRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit private val storageRejectionEncoder: Encoder.AsObject[StorageRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case StorageNotAccessible(_, details)        => obj.add("details", details.asJson)
        case WrappedOrganizationRejection(rejection) => rejection.asJsonObject
        case WrappedProjectRejection(rejection)      => rejection.asJsonObject
        case InvalidJsonLdFormat(_, details)         => obj.add("details", details.reason.asJson)
        case IncorrectRev(provided, expected)        => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                       => obj
      }
    }

  implicit final val storageRejectionJsonLdEncoder: JsonLdEncoder[StorageRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

}
