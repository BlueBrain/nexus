package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfError, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResolutionFetchRejection
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Storage rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class StorageRejection(val reason: String, val loggedDetails: Option[String] = None)
    extends Product
    with Serializable

object StorageRejection {

  /**
    * Rejection that may occur when fetching a Storage
    */
  sealed abstract class StorageFetchRejection(override val reason: String) extends StorageRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a storage at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends StorageFetchRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a storage at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends StorageFetchRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to update/fetch a storage with an id that doesn't exist.
    *
    * @param id      the storage identifier
    * @param project the project it belongs to
    */
  final case class StorageNotFound(id: Iri, project: ProjectRef)
      extends StorageFetchRejection(s"Storage '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to interact with a storage providing an id that cannot be resolved to an Iri.
    *
    * @param id  the storage identifier
    */
  final case class InvalidStorageId(id: String)
      extends StorageFetchRejection(s"Storage identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create a storage with an id that already exists.
    *
    * @param id      the storage identifier
    * @param project the project it belongs to
    */
  final case class StorageAlreadyExists(id: Iri, project: ProjectRef)
      extends StorageRejection(s"Storage '$id' already exists in project '$project'.")

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
    * Signals a rejection caused by an attempt to create or update a storage with permissions that are not
    * defined in the permission set singleton.
    *
    * @param permissions the provided permissions
    */
  final case class PermissionsAreNotDefined(permissions: Set[Permission])
      extends StorageRejection(
        s"The provided permissions '${permissions.mkString(",")}' are not defined in the collection of allowed permissions."
      )

  /**
    * Signals a rejection caused by the failure to encrypt/decrypt sensitive data (credentials)
    */
  final case class InvalidEncryptionSecrets(tpe: StorageType, details: String)
      extends StorageRejection(
        s"Storage type '$tpe' is using incorrect system secrets. Please contact the system administrator.",
        Some(
          s"Encryption/decryption for storage type '$tpe' fails due to wrong configuration for password or salt. Details '$details'."
        )
      )

  /**
    * Rejection returned when the associated project is invalid
    *
    * @param rejection the rejection which occurred with the project
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends StorageFetchRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends StorageFetchRejection(rejection.reason)

  /**
    * Rejection returned when the returned state is the initial state after a Storages.evaluation plus a Storages.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends StorageRejection(s"Unexpected initial state for storage '$id' of project '$project'.")

  private val logger: Logger = Logger("StorageRejection")

  implicit val storageJsonLdRejectionMapper: Mapper[JsonLdRejection, StorageRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedStorageId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit val storageProjectRejectionMapper: Mapper[ProjectRejection, StorageFetchRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val storageOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val storageResolutionRejectionMapper: Mapper[StorageFetchRejection, ResolutionFetchRejection] = {
    case InvalidStorageId(id)                    => ResolverResolutionRejection.InvalidId(id)
    case StorageNotFound(id, project)            => ResolverResolutionRejection.ResourceNotFound(id, project)
    case RevisionNotFound(provided, current)     => ResolverResolutionRejection.RevisionNotFound(provided, current)
    case TagNotFound(label)                      => ResolverResolutionRejection.TagNotFound(label)
    case WrappedProjectRejection(rejection)      => ResolverResolutionRejection.WrappedProjectRejection(rejection)
    case WrappedOrganizationRejection(rejection) => ResolverResolutionRejection.WrappedOrganizationRejection(rejection)
  }

  implicit private[plugins] val storageRejectionEncoder: Encoder.AsObject[StorageRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r.loggedDetails.foreach(logger.error(_))
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
