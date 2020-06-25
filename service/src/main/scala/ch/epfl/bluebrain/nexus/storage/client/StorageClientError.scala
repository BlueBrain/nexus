package ch.epfl.bluebrain.nexus.storage.client

import akka.http.scaladsl.model.StatusCode

import scala.reflect.ClassTag

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class StorageClientError(val message: String) extends Exception {
  override def fillInStackTrace(): StorageClientError = this
  override val getMessage: String                     = message
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object StorageClientError {

  final case class UnmarshallingError[A: ClassTag](reason: String)
      extends StorageClientError(
        s"Unable to parse or decode the response from Storage to a '${implicitly[ClassTag[A]]}' due to '$reason'."
      )

  final case class UnknownError(status: StatusCode, entityAsString: String)
      extends StorageClientError("The request did not complete successfully.")

  final case object EmptyChunk extends StorageClientError("Chunk with empty data")

  final case class NotFound(reason: String) extends StorageClientError(reason)

  final case class InvalidPath(reason: String) extends StorageClientError(reason)
}
