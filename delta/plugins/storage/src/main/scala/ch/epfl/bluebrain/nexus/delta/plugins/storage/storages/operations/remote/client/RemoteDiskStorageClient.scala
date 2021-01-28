package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection.UnexpectedMoveError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchFileRejection, MoveFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.{RemoteDiskStorageFileAttributes, RemoteDiskStorageServiceDescription}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO

/**
  * The client to communicate with the remote storage service
  */
final class RemoteDiskStorageClient(baseUri: BaseUri)(implicit client: HttpClient, as: ActorSystem) {
  import as.dispatcher

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: IO[HttpClientError, RemoteDiskStorageServiceDescription] =
    client.fromJsonTo[RemoteDiskStorageServiceDescription](Get(baseUri.base))

  /**
    * Checks that the provided storage bucket exists and it is readable/writable.
    *
    * @param bucket the storage bucket name
    */
  def exists(bucket: Label)(implicit cred: Option[AuthToken]): IO[HttpClientError, Unit] = {
    val endpoint = baseUri.endpoint / "buckets" / bucket.value
    val req      = Head(endpoint).withCredentials
    client(req) {
      case resp if resp.status.isSuccess() => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.unit
    }
  }

  /**
    * Creates a file with the provided metadata  and ''source'' on the provided ''relativePath''.
    *
    * @param bucket       the storage bucket name
    * @param relativePath the relative path location
    * @param source       the file content
    */
  def createFile(bucket: Label, relativePath: Path, source: AkkaSource)(implicit
      cred: Option[AuthToken]
  ): IO[SaveFileRejection, RemoteDiskStorageFileAttributes] = {
    val endpoint       = baseUri.endpoint / "buckets" / bucket.value / "files" / relativePath
    val bodyPartEntity = HttpEntity.IndefiniteLength(`application/octet-stream`, source)
    val filename       = relativePath.lastSegment.getOrElse("filename")
    val multipartForm  = FormData(BodyPart("file", bodyPartEntity, Map("filename" -> filename))).toEntity()
    client.fromJsonTo[RemoteDiskStorageFileAttributes](Put(endpoint, multipartForm).withCredentials).mapError {
      case HttpClientStatusError(_, `Conflict`, _) =>
        SaveFileRejection.FileAlreadyExists(relativePath.toString)
      case error                                   =>
        SaveFileRejection.UnexpectedSaveError(relativePath.toString, error.asString)
    }
  }

  /**
    * Retrieves the file as a Source.
    *
    * @param bucket       the storage bucket name
    * @param relativePath the relative path to the file location
    */
  def getFile(bucket: Label, relativePath: Path)(implicit
      cred: Option[AuthToken]
  ): IO[FetchFileRejection, AkkaSource] = {
    val endpoint = baseUri.endpoint / "buckets" / bucket.value / "files" / relativePath
    client.toDataBytes(Get(endpoint).withCredentials).mapError {
      case error @ HttpClientStatusError(_, `NotFound`, _) if !bucketNotFoundType(error) =>
        FetchFileRejection.FileNotFound(relativePath.toString)
      case error                                                                         =>
        UnexpectedFetchError(relativePath.toString, error.asString)
    }
  }

  /**
    * Retrieves the file attributes.
    *
    * @param bucket       the storage bucket name
    * @param relativePath the relative path to the file location
    */
  def getAttributes(
      bucket: Label,
      relativePath: Path
  )(implicit cred: Option[AuthToken]): IO[FetchFileRejection, RemoteDiskStorageFileAttributes] = {
    val endpoint = baseUri.endpoint / "buckets" / bucket.value / "attributes" / relativePath
    client.fromJsonTo[RemoteDiskStorageFileAttributes](Get(endpoint).withCredentials).mapError {
      case error @ HttpClientStatusError(_, `NotFound`, _) if !bucketNotFoundType(error) =>
        FetchFileRejection.FileNotFound(relativePath.toString)
      case error                                                                         =>
        UnexpectedFetchError(relativePath.toString, error.asString)
    }
  }

  /**
    * Moves a path from the provided ''sourceRelativePath'' to ''destRelativePath'' inside the nexus folder.
    *
    * @param bucket             the storage bucket name
    * @param sourceRelativePath the source relative path location
    * @param destRelativePath   the destination relative path location inside the nexus folder
    */
  def moveFile(
      bucket: Label,
      sourceRelativePath: Path,
      destRelativePath: Path
  )(implicit cred: Option[AuthToken]): IO[MoveFileRejection, RemoteDiskStorageFileAttributes] = {
    val endpoint = baseUri.endpoint / "buckets" / bucket.value / "files" / destRelativePath
    val payload  = Json.obj("source" -> sourceRelativePath.toString.asJson)
    client.fromJsonTo[RemoteDiskStorageFileAttributes](Put(endpoint, payload).withCredentials).mapError {
      case error @ HttpClientStatusError(_, `NotFound`, _) if !bucketNotFoundType(error)     =>
        MoveFileRejection.FileNotFound(sourceRelativePath.toString)
      case error @ HttpClientStatusError(_, `BadRequest`, _) if pathContainsLinksType(error) =>
        MoveFileRejection.PathContainsLinks(destRelativePath.toString)
      case HttpClientStatusError(_, `Conflict`, _)                                           =>
        MoveFileRejection.FileAlreadyExists(destRelativePath.toString)
      case error                                                                             =>
        UnexpectedMoveError(sourceRelativePath.toString, destRelativePath.toString, error.asString)
    }
  }

  private def bucketNotFoundType(error: HttpClientError): Boolean =
    error.jsonBody.fold(false)(_.hcursor.get[String](keywords.tpe).toOption.contains("BucketNotFound"))

  private def pathContainsLinksType(error: HttpClientError): Boolean =
    error.jsonBody.fold(false)(_.hcursor.get[String](keywords.tpe).toOption.contains("PathContainsLinks"))

}
