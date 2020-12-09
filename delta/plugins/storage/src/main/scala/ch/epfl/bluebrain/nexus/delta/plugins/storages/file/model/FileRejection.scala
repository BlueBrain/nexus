package ch.epfl.bluebrain.nexus.delta.plugins.storages.file.model

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of File rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class FileRejection(val reason: String) extends Product with Serializable

object FileRejection {

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
  final case class TagNotFound(tag: Label) extends FileRejection(s"Tag requested '$tag' not found.")

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
    * Rejection returned when interacting with the storage operations bundle to fetch a storage
    *
    * @param rejection the rejection which occurred with the storage
    */
  final case class WrappedStorageRejection(rejection: StorageRejection) extends FileRejection(rejection.reason)

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

  implicit val fileProjectRejectionMapper: Mapper[ProjectRejection, FileRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val fileStorageRejectionMapper: Mapper[StorageRejection, FileRejection] = { value =>
    WrappedStorageRejection(value)
  }

  implicit private val fileRejectionEncoder: Encoder.AsObject[FileRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case WrappedStorageRejection(rejection)      => rejection.asJsonObject
        case WrappedOrganizationRejection(rejection) => rejection.asJsonObject
        case WrappedProjectRejection(rejection)      => rejection.asJsonObject
        case IncorrectRev(provided, expected)        => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                       => obj
      }
    }

  implicit final val fileRejectionJsonLdEncoder: JsonLdEncoder[FileRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

}
