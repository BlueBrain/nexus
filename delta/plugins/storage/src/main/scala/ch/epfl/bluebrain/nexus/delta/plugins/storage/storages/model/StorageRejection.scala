package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Storage rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class StorageRejection(val reason: String, val loggedDetails: Option[String] = None) extends Rejection

object StorageRejection {

  /**
    * Rejection that may occur when fetching a Storage
    */
  sealed abstract class StorageFetchRejection(override val reason: String) extends StorageRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a storage at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends StorageFetchRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a storage at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: UserTag) extends StorageFetchRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to update/fetch a storage with an id that doesn't exist.
    *
    * @param id
    *   the storage identifier
    * @param project
    *   the project it belongs to
    */
  final case class StorageNotFound(id: Iri, project: ProjectRef)
      extends StorageFetchRejection(s"Storage '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to interact with a storage providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the storage identifier
    */
  final case class InvalidStorageId(id: String)
      extends StorageFetchRejection(s"Storage identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create a storage while providing an id that is blank.
    */
  final case object BlankStorageId extends StorageRejection(s"Storage identifier cannot be blank.")

  /**
    * Rejection returned when attempting to create a storage but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends StorageRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to fetch the default storage for a project but there is none.
    *
    * @param project
    *   the project it belongs to
    */
  final case class DefaultStorageNotFound(project: ProjectRef)
      extends StorageRejection(s"Default storage not found in project '$project'.")

  /**
    * Rejection returned when attempting to create/update a storage but it cannot be accessed.
    *
    * @param id
    *   the storage identifier
    */
  final case class StorageNotAccessible(id: Iri, details: String)
      extends StorageRejection(s"Storage '$id' not accessible.")

  /**
    * Rejection returned when attempting to create a storage where the passed id does not match the id on the payload.
    *
    * @param id
    *   the storage identifier
    * @param payloadId
    *   the storage identifier on the payload
    */
  final case class UnexpectedStorageId(id: Iri, payloadId: Iri)
      extends StorageRejection(s"Storage '$id' does not match storage id on payload '$payloadId'.")

  /**
    * Rejection when attempting to decode an expanded JsonLD as a case class
    * @param error
    *   the decoder error
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
    * Signals an attempt to update/create a storage based on a previous revision with a different storage type
    *
    * @param id
    * @param found
    * @param expected
    */
  final case class DifferentStorageType(id: Iri, found: StorageType, expected: StorageType)
      extends StorageRejection(s"Storage '$id' is of type '$found' and can't be updated to be a '$expected' .")

  /**
    * Rejection returned when attempting to create/update of a [[StorageType]] not supported by the platform.
    */
  final case class InvalidStorageType(id: Iri, found: StorageType, expected: Set[StorageType])
      extends StorageRejection(
        s"Storage '$id' of type '$found' is not supported. Supported storage types: '${expected.mkString(", ")}'"
      )

  /**
    * Rejection returned when a subject intends to perform an operation on the current storage, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends StorageRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the storage may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to update/deprecate a storage that is already deprecated.
    *
    * @param id
    *   the storage identifier
    */
  final case class StorageIsDeprecated(id: Iri) extends StorageRejection(s"Storage '$id' is deprecated.")

  /**
    * Signals a rejection caused by an attempt to create or update a storage with permissions that are not defined in
    * the permission set singleton.
    *
    * @param permissions
    *   the provided permissions
    */
  final case class PermissionsAreNotDefined(permissions: Set[Permission])
      extends StorageRejection(
        s"The provided permissions '${permissions.mkString(",")}' are not defined in the collection of allowed permissions."
      )

  /**
    * Signals a rejection caused when interacting with other APIs when fetching a resource
    */
  final case class ProjectContextRejection(rejection: ContextRejection)
      extends StorageFetchRejection("Something went wrong while interacting with another module.")

  implicit val storageJsonLdRejectionMapper: Mapper[JsonLdRejection, StorageRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedStorageId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
    case JsonLdRejection.BlankId                           => BlankStorageId
  }

  implicit private[plugins] val storageRejectionEncoder: Encoder.AsObject[StorageRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case StorageNotAccessible(_, details)   => obj.add("details", details.asJson)
        case ProjectContextRejection(rejection) => rejection.asJsonObject
        case InvalidJsonLdFormat(_, rdf)        => obj.add("rdf", rdf.asJson)
        case IncorrectRev(provided, expected)   => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case _: StorageNotFound                 => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                  => obj
      }
    }

  implicit final val storageRejectionJsonLdEncoder: JsonLdEncoder[StorageRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit final val storageRejectionHttpResponseFields: HttpResponseFields[StorageRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)       => StatusCodes.NotFound
      case TagNotFound(_)               => StatusCodes.NotFound
      case StorageNotFound(_, _)        => StatusCodes.NotFound
      case DefaultStorageNotFound(_)    => StatusCodes.NotFound
      case ResourceAlreadyExists(_, _)  => StatusCodes.Conflict
      case IncorrectRev(_, _)           => StatusCodes.Conflict
      case ProjectContextRejection(rej) => rej.status
      case StorageNotAccessible(_, _)   => StatusCodes.BadRequest
      case _                            => StatusCodes.BadRequest
    }

}
