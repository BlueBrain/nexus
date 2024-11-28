package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.sdk.NexusHeaders

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
      * Rejection returned when a file can not be saved because content-length is not provided
      */
    final case object FileContentLengthIsMissing
        extends SaveFileRejection(
          s"'${NexusHeaders.fileContentLength}' must be supplied when uploading a file to a S3 storage."
        )

    /**
      * Rejection returned when a storage cannot save a file due to an unexpected reason
      */
    final case class UnexpectedSaveError(path: String, details: String)
        extends SaveFileRejection(
          s"File cannot be saved on path '$path' for unexpected reasons. Details '$details'"
        )

    final case class BucketAccessDenied(bucket: String, key: String, details: String)
        extends SaveFileRejection(s"Access denied to bucket $bucket at key $key")
  }

  sealed abstract class LinkFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object LinkFileRejection {

    final case object Disabled extends LinkFileRejection(s"Linking a file is disabled")

    final case class InvalidPath(path: Uri.Path)
        extends LinkFileRejection(s"An S3 path must contain at least the filename. Path was $path")

    final case class UnsupportedOperation(tpe: StorageType)
        extends LinkFileRejection(s"Linking a file is not supported for storages of type '${tpe.iri}'")
  }

  sealed abstract class DelegateFileOperation(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object DelegateFileOperation {
    final case class UnsupportedOperation(tpe: StorageType)
        extends DelegateFileOperation(
          s"Delegating a file to be uploaded externally is not supported for storages of type '${tpe.iri}'"
        )
  }
}
