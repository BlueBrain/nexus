package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields

/**
  * Enumeration of Storage rejections related to file operations.
  */
sealed abstract class StorageFileRejection(val loggedDetails: String) extends Rejection {
  override def reason: String = loggedDetails
}

object StorageFileRejection {

  /**
    * Rejection returned when a storage cannot fetch a file
    */
  sealed abstract class FetchFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object FetchFileRejection {

    /**
      * Rejection returned when a file is not found using the storage with the passed ''id''
      */
    final case class FileNotFound(path: String)
        extends FetchFileRejection(s"File could not be retrieved from expected path '$path'.")

    /**
      * Rejection returned when a file's path is wrong or cannot be created
      */
    final case class UnexpectedLocationFormat(path: String, details: String)
        extends FetchFileRejection(
          s"File could not be retrieved from a wrong path '$path'. Details '$details'"
        )

    /**
      * Rejection returned when a storage cannot fetch a file due to an unexpected reason
      */
    final case class UnexpectedFetchError(path: String, details: String)
        extends FetchFileRejection(
          s"File cannot be fetched from path '$path' for unexpected reasons. Details '$details'"
        )

  }

  /**
    * Rejection returned when a storage cannot fetch a file's attributes
    */
  sealed abstract class FetchAttributeRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object FetchAttributeRejection {

    /**
      * Rejection performing this operation because the storage does not support it
      */
    final case class UnsupportedOperation(tpe: StorageType)
        extends FetchAttributeRejection(
          s"Fetching a file's attributes is not supported for storages of type '${tpe.iri}'"
        )

    /**
      * Rejection returned when a storage cannot fetch a file
      */
    final case class WrappedFetchRejection(rejection: FetchFileRejection)
        extends FetchAttributeRejection(rejection.loggedDetails)
  }

  sealed abstract class CopyFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object CopyFileRejection {
    final case class UnsupportedOperation(tpe: StorageType)
        extends CopyFileRejection(
          s"Copying a file attributes is not supported for storages of type '${tpe.iri}'"
        )

    final case class SourceFileTooLarge(maxSize: Long, storageId: Iri)
        extends CopyFileRejection(
          s"Source file size exceeds maximum $maxSize on destination storage $storageId"
        )

    final case class TotalCopySizeTooLarge(totalSize: Long, spaceLeft: Long, storageId: Iri)
        extends CopyFileRejection(
          s"Combined size of source files ($totalSize) exceeds space ($spaceLeft) on destination storage $storageId"
        )

    final case class RemoteDiskClientError(underlying: HttpClientError)
        extends CopyFileRejection(
          s"Error from remote disk storage client: ${underlying.asString}"
        )

    final case class DifferentStorageTypes(id: Iri, source: StorageType, dest: StorageType)
        extends CopyFileRejection(
          s"Source storage $id of type $source cannot be different to the destination storage type $dest"
        )

    implicit val statusCodes: HttpResponseFields[CopyFileRejection] = HttpResponseFields {
      case _: UnsupportedOperation  => StatusCodes.BadRequest
      case _: SourceFileTooLarge    => StatusCodes.BadRequest
      case _: TotalCopySizeTooLarge => StatusCodes.BadRequest
      case _: DifferentStorageTypes => StatusCodes.BadRequest
      case _: RemoteDiskClientError => StatusCodes.InternalServerError
    }
  }

  /**
    * Rejection returned when a storage cannot save a file
    */
  sealed abstract class SaveFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object SaveFileRejection {

    /**
      * Rejection returned when a file's path is wrong or cannot be created
      */
    final case class UnexpectedLocationFormat(path: String, details: String)
        extends SaveFileRejection(
          s"File could not be saved from a wrong path '$path'. Details '$details'"
        )

    /**
      * Rejection returned when a storage cannot create a directory when attempting to save a file
      */
    final case class CouldNotCreateIntermediateDirectory(path: String, details: String)
        extends SaveFileRejection(
          s"File could not be saved because the intermediate directory '$path' could not be created. Details $details"
        )

    /**
      * Rejection returned when a storage cannot save a file because it already exists
      */
    final case class ResourceAlreadyExists(path: String)
        extends SaveFileRejection(
          s"File cannot be saved because it already exists on path '$path'."
        )

    /**
      * Rejection returned when a storage cannot save a file due to an unexpected reason
      */
    final case class UnexpectedSaveError(path: String, details: String)
        extends SaveFileRejection(
          s"File cannot be saved on path '$path' for unexpected reasons. Details '$details'"
        )

  }

  /**
    * Rejection returned when a storage cannot move a file
    */
  sealed abstract class MoveFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object MoveFileRejection {

    /**
      * Rejection returned when a file is not found
      */
    final case class FileNotFound(sourcePath: String)
        extends MoveFileRejection(s"File could not be moved from expected path '$sourcePath'.")

    /**
      * Rejection returned when a storage cannot move a file because it already exists on its destination location
      */
    final case class ResourceAlreadyExists(destinationPath: String)
        extends MoveFileRejection(
          s"File cannot be moved because it already exists on its destination path '$destinationPath'."
        )

    /**
      * Rejection returned when a path to be moved contains links
      */
    final case class PathContainsLinks(path: String)
        extends MoveFileRejection(
          s"File could not be moved from path '$path' because the path contains links."
        )

    /**
      * Rejection returned when a storage cannot move a file due to an unexpected reason
      */
    final case class UnexpectedMoveError(sourcePath: String, destinationPath: String, details: String)
        extends MoveFileRejection(
          s"File cannot be moved from path '$sourcePath' to '$destinationPath' for unexpected reasons. Details '$details'"
        )

    /**
      * Rejection performing this operation because the storage does not support it
      */
    final case class UnsupportedOperation(tpe: StorageType)
        extends MoveFileRejection(s"Moving a file is not supported for storages of type '${tpe.iri}'")

  }

  sealed abstract class RegisterFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object RegisterFileRejection {
    final case class MissingS3Attributes(missingAttributes: NonEmptyList[String])
        extends RegisterFileRejection(s"Missing attributes from S3: ${missingAttributes.toList.mkString(", ")}")

    final case class InvalidContentType(received: String)
        extends RegisterFileRejection(s"Invalid content type returned from S3: $received")

    final case object MissingContentType extends RegisterFileRejection(s"Content type not returned from S3")

    final case class InvalidPath(path: Uri.Path)
        extends RegisterFileRejection(s"An S3 path must contain at least the filename. Path was $path")

    final case class UnsupportedOperation(tpe: StorageType)
        extends MoveFileRejection(s"Registering a file in-place is not supported for storages of type '${tpe.iri}'")
  }

}
