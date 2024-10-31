package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.BodyPartEntity
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.RemoteDiskStorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection.UnexpectedMoveError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchFileRejection, MoveFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, Json}

import scala.concurrent.duration._

/**
  * The client to communicate with the remote storage service
  */
trait RemoteDiskStorageClient {

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: IO[ServiceDescription]

  /**
    * Checks that the provided storage bucket exists and it is readable/writable.
    *
    * @param bucket
    *   the storage bucket name
    */
  def exists(bucket: Label): IO[Unit]

  /**
    * Creates a file with the provided metadata and ''source'' on the provided ''relativePath''.
    *
    * @param bucket
    *   the storage bucket name
    * @param relativePath
    *   the relative path location
    * @param entity
    *   the file content
    */
  def createFile(
      bucket: Label,
      relativePath: Path,
      entity: BodyPartEntity
  ): IO[RemoteDiskStorageFileAttributes]

  /**
    * Retrieves the file as a Source.
    *
    * @param bucket
    *   the storage bucket name
    * @param relativePath
    *   the relative path to the file location
    */
  def getFile(bucket: Label, relativePath: Path): IO[AkkaSource]

  /**
    * Retrieves the file attributes.
    *
    * @param bucket
    *   the storage bucket name
    * @param relativePath
    *   the relative path to the file location
    */
  def getAttributes(
      bucket: Label,
      relativePath: Path
  ): IO[RemoteDiskStorageFileAttributes]

  /**
    * Moves a path from the provided ''sourceRelativePath'' to ''destRelativePath'' inside the nexus folder.
    *
    * @param bucket
    *   the storage bucket name
    * @param sourceRelativePath
    *   the source relative path location
    * @param destRelativePath
    *   the destination relative path location inside the nexus folder
    */
  def moveFile(
      bucket: Label,
      sourceRelativePath: Path,
      destRelativePath: Path
  ): IO[RemoteDiskStorageFileAttributes]
}

object RemoteDiskStorageClient {

  final class RemoteDiskStorageClientImpl(
      client: HttpClient,
      getAuthToken: AuthTokenProvider,
      baseUri: BaseUri,
      credentials: Credentials
  )(implicit as: ActorSystem)
      extends RemoteDiskStorageClient {

    import as.dispatcher

    private val serviceName = "remoteStorage"

    def serviceDescription: IO[ServiceDescription] =
      client
        .fromJsonTo[ResolvedServiceDescription](Get(baseUri.base))
        .map(_.copy(name = serviceName))
        .widen[ServiceDescription]
        .timeout(3.seconds)
        .recover(_ => ServiceDescription.unresolved(serviceName))

    def exists(bucket: Label): IO[Unit] = {
      getAuthToken(credentials).flatMap { authToken =>
        val endpoint = baseUri.endpoint / "buckets" / bucket.value
        val req      = Head(endpoint).withCredentials(authToken)
        client(req) {
          case resp if resp.status.isSuccess() => IO.delay(resp.discardEntityBytes()).void
        }
      }
    }

    def createFile(
        bucket: Label,
        relativePath: Path,
        entity: BodyPartEntity
    ): IO[RemoteDiskStorageFileAttributes] = {
      getAuthToken(credentials).flatMap { authToken =>
        val endpoint      = baseUri.endpoint / "buckets" / bucket.value / "files" / relativePath
        val filename      = relativePath.lastSegment.getOrElse("filename")
        val multipartForm = FormData(BodyPart("file", entity, Map("filename" -> filename))).toEntity()
        client
          .fromJsonTo[RemoteDiskStorageFileAttributes](Put(endpoint, multipartForm).withCredentials(authToken))
          .adaptError {
            case HttpClientStatusError(_, `Conflict`, _, _) =>
              SaveFileRejection.ResourceAlreadyExists(relativePath.toString)
            case error: HttpClientError                     =>
              SaveFileRejection.UnexpectedSaveError(relativePath.toString, error.asString)
          }
      }
    }

    def getFile(bucket: Label, relativePath: Path): IO[AkkaSource] = {
      getAuthToken(credentials).flatMap { authToken =>
        val endpoint = baseUri.endpoint / "buckets" / bucket.value / "files" / relativePath
        client
          .toDataBytes(Get(endpoint).withCredentials(authToken))
          .adaptError {
            case error @ HttpClientStatusError(_, `NotFound`, _, _) if !bucketNotFoundType(error) =>
              FetchFileRejection.FileNotFound(relativePath.toString)
            case error: HttpClientError                                                           =>
              UnexpectedFetchError(relativePath.toString, error.asString)
          }
      }
    }

    def getAttributes(
        bucket: Label,
        relativePath: Path
    ): IO[RemoteDiskStorageFileAttributes] = {
      getAuthToken(credentials).flatMap { authToken =>
        val endpoint = baseUri.endpoint / "buckets" / bucket.value / "attributes" / relativePath
        client.fromJsonTo[RemoteDiskStorageFileAttributes](Get(endpoint).withCredentials(authToken)).adaptError {
          case error @ HttpClientStatusError(_, `NotFound`, _, _) if !bucketNotFoundType(error) =>
            FetchFileRejection.FileNotFound(relativePath.toString)
          case error: HttpClientError                                                           =>
            UnexpectedFetchError(relativePath.toString, error.asString)
        }
      }
    }

    def moveFile(
        bucket: Label,
        sourceRelativePath: Path,
        destRelativePath: Path
    ): IO[RemoteDiskStorageFileAttributes] = {
      getAuthToken(credentials).flatMap { authToken =>
        val endpoint = baseUri.endpoint / "buckets" / bucket.value / "files" / destRelativePath
        val payload  = Json.obj("source" -> sourceRelativePath.toString.asJson)
        client
          .fromJsonTo[RemoteDiskStorageFileAttributes](Put(endpoint, payload).withCredentials(authToken))
          .adaptError {
            case error @ HttpClientStatusError(_, `NotFound`, _, _) if !bucketNotFoundType(error)     =>
              MoveFileRejection.FileNotFound(sourceRelativePath.toString)
            case error @ HttpClientStatusError(_, `BadRequest`, _, _) if pathContainsLinksType(error) =>
              MoveFileRejection.PathContainsLinks(destRelativePath.toString)
            case HttpClientStatusError(_, `Conflict`, _, _)                                           =>
              MoveFileRejection.ResourceAlreadyExists(destRelativePath.toString)
            case error: HttpClientError                                                               =>
              UnexpectedMoveError(sourceRelativePath.toString, destRelativePath.toString, error.asString)
          }
      }
    }

    private def bucketNotFoundType(error: HttpClientError): Boolean =
      error.jsonBody.fold(false)(_.hcursor.get[String](keywords.tpe).toOption.contains("BucketNotFound"))

    private def pathContainsLinksType(error: HttpClientError): Boolean =
      error.jsonBody.fold(false)(_.hcursor.get[String](keywords.tpe).toOption.contains("PathContainsLinks"))

    implicit private val resolvedServiceDescriptionDecoder: Decoder[ResolvedServiceDescription] =
      deriveDecoder[ResolvedServiceDescription]

  }

  final object RemoteDiskStorageClientDisabled extends RemoteDiskStorageClient {

    private val disabledError = IO.raiseError(FeatureDisabled("Remote storage is disabled"))

    override def serviceDescription: IO[ServiceDescription] = disabledError

    override def exists(bucket: Label): IO[Unit] = disabledError

    override def createFile(
        bucket: Label,
        relativePath: Path,
        entity: BodyPartEntity
    ): IO[RemoteDiskStorageFileAttributes] = disabledError

    override def getFile(bucket: Label, relativePath: Path): IO[AkkaSource] = disabledError

    override def getAttributes(bucket: Label, relativePath: Path): IO[RemoteDiskStorageFileAttributes] = disabledError

    override def moveFile(
        bucket: Label,
        sourceRelativePath: Path,
        destRelativePath: Path
    ): IO[RemoteDiskStorageFileAttributes] = disabledError
  }

  def apply(client: HttpClient, authTokenProvider: AuthTokenProvider, configOpt: Option[RemoteDiskStorageConfig])(
      implicit as: ActorSystem
  ): RemoteDiskStorageClient =
    configOpt
      .map { config =>
        new RemoteDiskStorageClientImpl(
          client,
          authTokenProvider,
          config.defaultEndpoint,
          config.credentials
        )(as)
      }
      .getOrElse(RemoteDiskStorageClientDisabled)
}
