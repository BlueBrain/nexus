package ai.senscience.nexus.delta.plugins.archive

import ai.senscience.nexus.delta.plugins.archive.model.ArchiveReference.{FileReference, FileSelfReference, ResourceReference}
import ai.senscience.nexus.delta.plugins.archive.model.ArchiveRejection.{InvalidFileSelf, ResourceNotFound}
import ai.senscience.nexus.delta.plugins.archive.model.{ArchiveRejection, ArchiveValue}
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import cats.data.NonEmptySet
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encodeUri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf.ParsingError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes, MediaType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.AbsolutePath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{FileSelf, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRepresentation.{CompactedJsonLd, Dot, ExpandedJsonLd, NQuads, NTriples, SourceJson}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.test.archive.ArchiveHelpers
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax.EncoderOps
import org.http4s.Uri
import org.scalactic.source.Position

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class ArchiveDownloadSpec
    extends TestKit(ActorSystem())
    with CatsEffectSpec
    with StorageFixtures
    with ArchiveHelpers
    with RemoteContextResolutionFixture {

  implicit val ec: ExecutionContext = system.dispatcher

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject)
  implicit private val baseUri: BaseUri = BaseUri.unsafe("http://localhost", "v1")

  implicit private val jsonKeyOrdering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  private val project    =
    ProjectGen.project("org", "proj", base = nxv.base, mappings = ApiMappings("file" -> schemas.files))
  private val projectRef = project.ref

  private val permissions = Set(Permissions.resources.read)
  private val aclCheck    = AclSimpleCheck((subject, AclAddress.Root, permissions)).accepted

  def sourceToMap(source: AkkaSource): Map[String, String] = fromZip(source).map { case (k, v) => k -> v.utf8String }

  "An ArchiveDownload" should {
    val storageRef                                    = ResourceRef.Revision(iri"http://localhost/${genString()}", 5)
    def fileAttributes(filename: String, bytes: Long) = FileAttributes(
      UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc"),
      Uri.unsafeFromString("http://localhost/file.txt"),
      Uri.Path.unsafeFromString("file.txt"),
      filename,
      Some(MediaType.`text/plain`),
      Map.empty,
      None,
      None,
      bytes,
      Digest.NotComputedDigest,
      Client
    )

    val id1                  = iri"http://localhost/${genString()}"
    val file1Name            = "file.txt"
    val file1Size            = 12L
    val file1                = FileGen.resourceFor(id1, projectRef, storageRef, fileAttributes(file1Name, file1Size))
    val file1Content: String = "file content"
    val file1Self            = uri"http://delta:8080/files" / id1.toString

    val id2                  = iri"http://localhost/${genString()}"
    val file2Name            = genString(100)
    val file2Size            = 14L
    val file2                = FileGen.resourceFor(id2, projectRef, storageRef, fileAttributes(file2Name, file2Size))
    val file2Content: String = "file content 2"

    val fetchResource: (Iri, ProjectRef) => IO[Option[JsonLdContent[?]]] = {
      case (`id1`, `projectRef`) =>
        IO.pure(Some(JsonLdContent(file1, file1.value.asJson, file1.value.tags)))
      case (`id2`, `projectRef`) =>
        IO.pure(Some(JsonLdContent(file2, file2.value.asJson, file2.value.tags)))
      case _                     =>
        IO.none
    }

    val file1SelfIri: Iri  = file1Self.toIri
    val fileSelf: FileSelf = {
      case `file1SelfIri` => IO.pure((projectRef, Latest(id1)))
      case other          => IO.raiseError(ParsingError.InvalidPath(other))
    }

    val fetchFileContent: (Iri, ProjectRef) => IO[FileResponse] = {
      case (`id1`, `projectRef`) =>
        IO.pure(
          FileResponse.noCache(file1Name, `text/plain(UTF-8)`, Some(file1Size), Source.single(ByteString(file1Content)))
        )
      case (`id2`, `projectRef`) =>
        IO.pure(
          FileResponse.noCache(file2Name, `text/plain(UTF-8)`, Some(file2Size), Source.single(ByteString(file2Content)))
        )
      case (id, ref)             =>
        IO.raiseError(FileNotFound(id, ref))
    }

    val archiveDownload = ArchiveDownload(
      aclCheck,
      (id: ResourceRef, ref: ProjectRef) => fetchResource(id.iri, ref),
      (id: ResourceRef, ref: ProjectRef, _: Caller) => fetchFileContent(id.iri, ref),
      fileSelf
    )

    def downloadAndExtract(value: ArchiveValue, ignoreNotFound: Boolean)(implicit pos: Position) = {
      archiveDownload(value, project.ref, ignoreNotFound).map(sourceToMap).accepted
    }

    def failToDownload[R <: ArchiveRejection: ClassTag](value: ArchiveValue, ignoreNotFound: Boolean)(implicit
        pos: Position
    ) = {
      archiveDownload(value, project.ref, ignoreNotFound).rejectedWith[R]
    }

    def rejectedAccess(value: ArchiveValue)(implicit pos: Position) = {
      archiveDownload
        .apply(value, project.ref, ignoreNotFound = true)(Caller.Anonymous)
        .rejectedWith[AuthorizationFailed]
    }

    s"provide a zip for both resources and files" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(id1), None, None, None),
          FileReference(Latest(id1), None, None)
        )
      )
      val result   = downloadAndExtract(value, ignoreNotFound = false)
      val expected = Map(
        s"${project.ref.toString}/compacted/${encodeUri(file1.id.toString)}.json" -> file1.toCompactedJsonLd.accepted.json.sort.spaces2,
        s"${project.ref.toString}/file/${file1.value.attributes.filename}"        -> file1Content
      )
      result shouldEqual expected
    }

    s"provide a zip for file selfs" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          FileSelfReference(file1Self, None)
        )
      )
      val result   = downloadAndExtract(value, ignoreNotFound = false)
      val expected = Map(
        s"${project.ref.toString}/file/${file1.value.attributes.filename}" -> file1Content
      )
      result shouldEqual expected
    }

    s"fail to provide a zip for file selfs which do not resolve" in {
      val value = ArchiveValue.unsafe(NonEmptySet.of(FileSelfReference(uri"http://wrong.file/self", None)))
      failToDownload[InvalidFileSelf](value, ignoreNotFound = false)
    }

    s"provide a zip for both resources and files with different paths and formats" in {
      val list = List(
        SourceJson      -> file1.value.asJson.sort.spaces2,
        CompactedJsonLd -> file1.toCompactedJsonLd.accepted.json.sort.spaces2,
        ExpandedJsonLd  -> file1.toExpandedJsonLd.accepted.json.sort.spaces2,
        NTriples        -> file1.toNTriples.accepted.value,
        NQuads          -> file1.toNQuads.accepted.value,
        Dot             -> file1.toDot.accepted.value
      )
      forAll(list) { case (repr, expectedString) =>
        val filePath     = AbsolutePath.apply(s"/${genString()}/file.txt").rightValue
        val resourcePath = AbsolutePath.apply(s"/${genString()}/file${repr.extension}").rightValue
        val value        = ArchiveValue.unsafe(
          NonEmptySet.of(
            ResourceReference(Latest(id1), None, Some(resourcePath), Some(repr)),
            FileReference(Latest(id1), None, Some(filePath))
          )
        )
        val result       = downloadAndExtract(value, ignoreNotFound = false)
        if (repr == Dot) {
          result(resourcePath.value.toString).contains(s"""digraph "$id1"""") shouldEqual true
        } else if (repr == NTriples || repr == NQuads) {
          result(resourcePath.value.toString).contains(s"""<$id1>""") shouldEqual true
        } else {
          val expected = Map(
            resourcePath.value.toString -> expectedString,
            filePath.value.toString     -> file1Content
          )
          result shouldEqual expected
        }
      }
    }

    "provide a zip if the file name is long" in {
      val value     = ArchiveValue.unsafe(
        NonEmptySet.of(
          FileReference(Latest(id2), None, None)
        )
      )
      val file2Path = s"${project.ref.toString}/file/${file2.value.attributes.filename}"
      downloadAndExtract(value, ignoreNotFound = false) should contain key file2Path
    }

    s"fail to provide a zip when a resource is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(iri"http://localhost/${genString()}"), None, None, None),
          FileReference(Latest(id1), None, None)
        )
      )
      failToDownload[ResourceNotFound](value, ignoreNotFound = false)
    }

    s"fail to provide a zip when a file is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(id1), None, None, None),
          FileReference(Latest(iri"http://localhost/${genString()}"), None, None)
        )
      )
      failToDownload[ResourceNotFound](value, ignoreNotFound = false)
    }

    "ignore missing resources" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(iri"http://localhost/${genString()}"), None, None, None),
          FileReference(Latest(id1), None, None)
        )
      )
      val result   = downloadAndExtract(value, ignoreNotFound = true)
      val expected = Map(
        s"${project.ref.toString}/file/${file1.value.attributes.filename}" -> file1Content
      )
      result shouldEqual expected
    }

    "ignore missing files" in {
      val value    = ArchiveValue.unsafe(
        NonEmptySet.of(
          ResourceReference(Latest(id1), None, None, None),
          FileReference(Latest(iri"http://localhost/${genString()}"), None, None)
        )
      )
      val result   = downloadAndExtract(value, ignoreNotFound = true)
      val expected = Map(
        s"${project.ref.toString}/compacted/${encodeUri(file1.id.toString)}.json" -> file1.toCompactedJsonLd.accepted.json.sort.spaces2
      )
      result shouldEqual expected
    }

    s"fail to provide a zip when access to a resource is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(ResourceReference(Latest(id1), None, None, None))
      )
      rejectedAccess(value)
    }

    s"fail to provide a zip when access to a file is not found" in {
      val value = ArchiveValue.unsafe(
        NonEmptySet.of(FileReference(Latest(id1), None, None))
      )
      rejectedAccess(value)
    }
  }
}
