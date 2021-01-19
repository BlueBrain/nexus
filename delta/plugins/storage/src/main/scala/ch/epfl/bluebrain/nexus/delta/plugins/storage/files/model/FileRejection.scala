package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.server.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler.all._
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of File rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class FileRejection(val reason: String, val loggedDetails: Option[String] = None)
    extends Product
    with Serializable

object FileRejection {

  private val logger: Logger = Logger[FileRejection]

  /**
    * Rejection returned when a subject intends to retrieve a file at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends FileRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a file at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends FileRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a file with an id that already exists.
    *
    * @param id      the file identifier
    * @param project the project it belongs to
    */
  final case class FileAlreadyExists(id: Iri, project: ProjectRef)
      extends FileRejection(s"File '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update a file with an id that doesn't exist.
    *
    * @param id      the file identifier
    * @param project the project it belongs to
    */
  final case class FileNotFound(id: Iri, project: ProjectRef)
      extends FileRejection(s"File '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to interact with a file providing an id that cannot be resolved to an Iri.
    *
    * @param id  the file identifier
    */
  final case class InvalidFileId(id: String)
      extends FileRejection(s"File identifier '$id' cannot be expanded to an Iri.")

  /**
    * Signals the impossibility to update a file when the digest is not computed
    *
    * @param id the file identifier
    */
  final case class DigestNotComputed(id: Iri)
      extends FileRejection(
        s"The digest computation for the current file '$id' is not yet complete; the file cannot be updated"
      )

  /**
    * Signals that the digest of the file has already been computed
    *
    * @param id the file identifier
    */
  final case class DigestAlreadyComputed(id: Iri)
      extends FileRejection(s"The digest computation for the current file '$id' has already been completed")

  /**
    * Rejection returned when a subject intends to perform an operation on the current file, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends FileRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the file may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to update/deprecate a file that is already deprecated.
    *
    * @param id the file identifier
    */
  final case class FileIsDeprecated(id: Iri) extends FileRejection(s"File '$id' is deprecated.")

  /**
    * Rejection returned when attempting to link a file without providing a filename or a path that ends with a filename.
    *
    * @param id the file identifier
    */
  final case class InvalidFileLink(id: Iri)
      extends FileRejection(
        s"Linking a file '$id' cannot be performed without a 'filename' or a 'path' that does not end with a filename."
      )

  /**
    * Rejection returned when attempting to create/update a file with a Multipart/Form-Data payload that does not contain
    * a ''file'' fieldName
    */
  final case class InvalidMultipartFieldName(id: Iri)
      extends FileRejection(s"File '$id' payload a Multipart/Form-Data without a 'file' part.")

  /**
    * Rejection returned when attempting to interact with a file and the caller does not have the right permissions
    * defined in the storage.
    */
  final case object AuthorizationFailed extends FileRejection(ServiceError.AuthorizationFailed.reason)

  type AuthorizationFailed = AuthorizationFailed.type

  /**
    * Rejection returned when attempting to create/update a file and the unmarshaller fails
    */
  final case class WrappedAkkaRejection(rejection: Rejection) extends FileRejection(rejection.toString)

  /**
    * Rejection returned when interacting with the storage operations bundle to fetch a storage
    *
    * @param rejection the rejection which occurred with the storage
    */
  final case class WrappedStorageRejection(rejection: StorageRejection) extends FileRejection(rejection.reason)

  /**
    * Rejection returned when interacting with the storage operations bundle to fetch a file from a storage
    *
    * @param id        the file id
    * @param storageId the storage id
    * @param rejection the rejection which occurred with the storage
    */
  final case class FetchRejection(id: Iri, storageId: Iri, rejection: StorageFileRejection.FetchFileRejection)
      extends FileRejection(
        s"File '$id' could not be fetched using storage '$storageId'",
        Some(rejection.loggedDetails)
      )

  /**
    * Rejection returned when interacting with the storage operations bundle to fetch a file attributes from a storage
    *
    * @param id        the file id
    * @param storageId the storage id
    * @param rejection the rejection which occurred with the storage
    */
  final case class FetchAttributesRejection(
      id: Iri,
      storageId: Iri,
      rejection: StorageFileRejection.FetchAttributeRejection
  ) extends FileRejection(s"Attributes of file '$id' could not be fetched using storage '$storageId'")

  /**
    * Rejection returned when interacting with the storage operations bundle to save a file in a storage
    *
    * @param id        the file id
    * @param storageId the storage id
    * @param rejection the rejection which occurred with the storage
    */
  final case class SaveRejection(id: Iri, storageId: Iri, rejection: StorageFileRejection.SaveFileRejection)
      extends FileRejection(s"File '$id' could not be saved using storage '$storageId'", Some(rejection.loggedDetails))

  /**
    * Rejection returned when interacting with the storage operations bundle to move a file in a storage
    *
    * @param id        the file id
    * @param storageId the storage id
    * @param rejection the rejection which occurred with the storage
    */
  final case class MoveRejection(id: Iri, storageId: Iri, rejection: StorageFileRejection.MoveFileRejection)
      extends FileRejection(s"File '$id' could not be moved using storage '$storageId'", Some(rejection.loggedDetails))

  /**
    * Rejection returned when the associated project is invalid
    *
    * @param rejection the rejection which occurred with the project
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends FileRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends FileRejection(rejection.reason)

  /**
    * Rejection returned when the returned state is the initial state after a Files.evaluation plus a Files.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends FileRejection(s"Unexpected initial state for file '$id' of project '$project'.")

  implicit val fileProjectRejectionMapper: Mapper[ProjectRejection, FileRejection]                 = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }
  implicit val fileOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val fileStorageFetchRejectionMapper: Mapper[StorageFetchRejection, WrappedStorageRejection] =
    (value: StorageFetchRejection) => WrappedStorageRejection(value)

  implicit private val fileRejectionEncoder: Encoder.AsObject[FileRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r.loggedDetails.foreach(loggedDetails => logger.error(s"${r.reason}. Details '$loggedDetails'"))
      r match {
        case WrappedAkkaRejection(rejection)           => rejection.asJsonObject
        case WrappedStorageRejection(rejection)        => rejection.asJsonObject
        case SaveRejection(_, _, rejection)            => obj.add(keywords.tpe, ClassUtils.simpleName(rejection).asJson)
        case FetchRejection(_, _, rejection)           => obj.add(keywords.tpe, ClassUtils.simpleName(rejection).asJson)
        case FetchAttributesRejection(_, _, rejection) => obj.add(keywords.tpe, ClassUtils.simpleName(rejection).asJson)
        case MoveRejection(_, _, rejection)            => obj.add(keywords.tpe, ClassUtils.simpleName(rejection).asJson)
        case WrappedOrganizationRejection(rejection)   => rejection.asJsonObject
        case WrappedProjectRejection(rejection)        => rejection.asJsonObject
        case IncorrectRev(provided, expected)          => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                         => obj
      }
    }

  implicit final val fileRejectionJsonLdEncoder: JsonLdEncoder[FileRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

}
