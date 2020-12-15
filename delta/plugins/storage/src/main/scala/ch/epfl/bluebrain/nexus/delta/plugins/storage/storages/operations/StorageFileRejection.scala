package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

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
    final case class FileNotFound(id: Iri, path: String)
        extends FetchFileRejection(s"File could not be retrieved from expected path '$path' using storage '$id'.")

    /**
      * Rejection returned when a file's path cannot be created
      */
    final case class UnexpectedLocationFormat(id: Iri, path: String, details: String)
        extends FetchFileRejection(s"File could not be retrieved from a wrong path '$path' using storage '$id'.")

    /**
      * Rejection returned when a storage cannot fetch a file due to an unexpected reason
      */
    final case class UnexpectedFetchError(id: Iri, path: String, details: String)
        extends FetchFileRejection(
          s"File cannot be fetched from path '$path' for unexpected reasons using storage '$id'."
        )

  }

  /**
    * Rejection returned when a storage cannot save a file
    */
  sealed abstract class SaveFileRejection(loggedDetails: String) extends StorageFileRejection(loggedDetails)

  object SaveFileRejection {

    /**
      * Rejection returned when a file's path cannot be created
      */
    final case class UnexpectedLocationFormat(id: Iri, path: String, details: String)
        extends SaveFileRejection(s"File could not be saved from a wrong path '$path' using storage '$id'.")

    /**
      * Rejection returned when a storage cannot create a directory when attempting to save a file
      */
    final case class CouldNotCreateIntermediateDirectory(id: Iri, path: String, details: String)
        extends SaveFileRejection(
          s"File could not be saved because the intermediate directory '$path' could not be created using storage '$id'. Details $details"
        )

    /**
      * Rejection returned when a storage cannot save a file because it already exists
      */
    final case class FileAlreadyExists(id: Iri, path: String)
        extends SaveFileRejection(
          s"File cannot be saved because it already exists on path '$path' using storage '$id'."
        )

    /**
      * Rejection returned when a storage cannot save a file due to an unexpected reason
      */
    final case class UnexpectedSaveError(id: Iri, path: String, details: String)
        extends SaveFileRejection(s"File cannot be saved on path '$path' for unexpected reasons using storage '$id'.")

  }

}
