package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, MediaType}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.InvalidFilePath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes, FileLinkRequest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{permissions, MediaTypeDetector}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.FetchStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.{S3FileLink, S3FileMetadata}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import org.http4s.Uri

import java.util.UUID

class LinkFileActionSuite extends NexusSuite {

  private val realm           = Label.unsafe("myrealm")
  private val user            = User("user", realm)
  implicit val caller: Caller = Caller(user, Set.empty)

  private val project    = ProjectRef.unsafe("org", "project")
  private val storageIri = nxv + "s3-storage"
  private val storageRef = ResourceRef.Revision(storageIri, 1)
  private val value      = S3StorageValue(default = false, "bucket", permissions.read, permissions.write, 100L)
  private val s3Storage  = S3Storage(storageIri, project, value, Json.Null)

  private val fetchStorage = new FetchStorage {

    override def onRead(id: ResourceRef, project: ProjectRef)(implicit caller: Caller): IO[Storage] =
      IO.raiseError(new IllegalStateException("Should not be called"))

    override def onWrite(id: Option[IriOrBNode.Iri], project: ProjectRef)(implicit
        caller: Caller
    ): IO[(ResourceRef.Revision, Storage)] =
      IO.raiseUnless(id.contains(storageIri))(AuthorizationFailed("Fail")) >> IO.pure(storageRef -> s3Storage)
  }

  private val mediaTypeDetector = new MediaTypeDetector(MediaTypeDetectorConfig.Empty)

  private val uuid              = UUID.randomUUID()
  private val contentTypeFromS3 = ContentTypes.`application/octet-stream`
  private val fileSize          = 100L
  private val digest            = Digest.NoDigest

  private val s3FileLink: S3FileLink = (_: String, path: Uri.Path) => {
    IO.fromOption(path.lastSegment)(InvalidFilePath).map { filename =>
      S3FileMetadata(
        filename,
        Some(contentTypeFromS3),
        FileStorageMetadata(
          uuid,
          fileSize,
          digest,
          FileAttributesOrigin.Link,
          Uri(path = path),
          path
        )
      )
    }
  }

  private val linkAction = LinkFileAction(fetchStorage, mediaTypeDetector, s3FileLink)

  test("Fail for an unauthorized storage") {
    val request = FileLinkRequest(Uri.Path.unsafeFromString("/path/file.json"), None, None)
    linkAction(None, project, request).intercept[AuthorizationFailed]
  }

  test("Succeed for a file with media type detection") {
    val request    = FileLinkRequest(Uri.Path.unsafeFromString("/path/file.json"), None, None)
    val attributes = FileAttributes(
      uuid,
      Uri(path = request.path),
      request.path,
      "file.json",
      Some(ContentTypes.`application/json`),
      Map.empty,
      None,
      None,
      fileSize,
      digest,
      FileAttributesOrigin.Link
    )
    val expected   = StorageWrite(storageRef, StorageType.S3Storage, attributes)
    linkAction(Some(storageIri), project, request).assertEquals(expected)
  }

  test("Succeed for a file without media type detection") {
    val request    = FileLinkRequest(Uri.Path.unsafeFromString("/path/file.obj"), None, None)
    val attributes = FileAttributes(
      uuid,
      Uri(path = request.path),
      request.path,
      "file.obj",
      Some(contentTypeFromS3),
      Map.empty,
      None,
      None,
      fileSize,
      digest,
      FileAttributesOrigin.Link
    )
    val expected   = StorageWrite(storageRef, StorageType.S3Storage, attributes)
    linkAction(Some(storageIri), project, request).assertEquals(expected)
  }

  test("Succeed for a file with provided media type") {
    val customMediaType   = MediaType.custom("application/obj", binary = false)
    val customContentType = ContentType(customMediaType, () => HttpCharsets.`UTF-8`)
    val request           = FileLinkRequest(Uri.Path.unsafeFromString("/path/file.obj"), Some(customContentType), None)
    val attributes        = FileAttributes(
      uuid,
      Uri(path = request.path),
      request.path,
      "file.obj",
      Some(customContentType),
      Map.empty,
      None,
      None,
      fileSize,
      digest,
      FileAttributesOrigin.Link
    )
    val expected          = StorageWrite(storageRef, StorageType.S3Storage, attributes)
    linkAction(Some(storageIri), project, request).assertEquals(expected)
  }

}
