package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType

/**
  * Enumeration of Storage rejections related to file operations.
  */
sealed abstract class StorageFileRejection(val loggedDetails: String) extends Product with Serializable

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
    final case class FileAlreadyExists(path: String)
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
    final case class FileAlreadyExists(destinationPath: String)
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

}
