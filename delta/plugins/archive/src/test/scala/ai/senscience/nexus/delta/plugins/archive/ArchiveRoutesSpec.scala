package ai.senscience.nexus.delta.plugins.archive

import ai.senscience.nexus.delta.plugins.archive.routes.ArchiveRoutes
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`application/zip`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Location}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.{encodeUri, encodeUriPath}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{StatefulUUIDF, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf.ParsingError.InvalidPath
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.generators.FileGen
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileAttributes, MediaType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutesSpec
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.schemas
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceAccess, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EphemeralLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.test.archive.ArchiveHelpers
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.Uri

import java.util.UUID
import scala.concurrent.duration.*

class ArchiveRoutesSpec extends BaseRouteSpec with StorageFixtures with ArchiveHelpers {

  private val uuid                          = UUID.fromString("8249ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidF: StatefulUUIDF = UUIDF.stateful(uuid).accepted

  implicit override def rcr: RemoteContextResolution = RemoteContextResolutionFixture.rcr

  private val readAll      = User("readAll", Label.unsafe("realm"))
  private val noFileAccess = User("noFileAccess", Label.unsafe("realm"))

  private val project    =
    ProjectGen.project("org", "proj", base = nxv.base, mappings = ApiMappings("file" -> schemas.files))
  private val projectRef = project.ref

  implicit private val jsonKeyOrdering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  private val archivesConfig = ArchivePluginConfig(1, EphemeralLogConfig(5.seconds, 5.hours))

  private val perms = Seq(
    Permissions.resources.write,
    Permissions.resources.read
  )

  private val acceptMeta   = Accept(`application/ld+json`)
  private val acceptAll    = Accept(`*/*`)
  private val acceptZip    = Accept(`application/zip`)
  private val acceptJsonLd = Accept(`application/ld+json`)

  private val fetchContext = FetchContextDummy(List(project))

  private val storageRef = ResourceRef.Revision(iri"http://localhost/${genString()}", 5)

  private val fileId                = iri"http://localhost/${genString()}"
  private val fileSelf              = uri"http://delta:8080/files" / fileId.toString
  private val fileContent           = "file content"
  private val fileAttributes        = FileAttributes(
    uuid,
    Uri.unsafeFromString("http://localhost/file.txt"),
    Uri.Path.unsafeFromString("file.txt"),
    "myfile",
    Some(MediaType.`text/plain`),
    Map.empty,
    None,
    None,
    12L,
    ComputedDigest(DigestAlgorithm.default, "digest"),
    Client
  )
  private val file: ResourceF[File] =
    FileGen.resourceFor(fileId, projectRef, storageRef, fileAttributes, createdBy = readAll, updatedBy = readAll)

  private val notFoundId   = uri"http://localhost" / genString()
  private val notFoundSelf = uri"http://delta:8080/files" / notFoundId.toString

  private val generatedId = project.base.iri / uuid.toString

  val fetchResource: (Iri, ProjectRef) => IO[Option[JsonLdContent[?]]] = {
    case (`fileId`, `projectRef`) =>
      IO.pure(Some(JsonLdContent(file, file.value.asJson, file.value.tags)))
    case _                        =>
      IO.none
  }

  val fetchFileContent: (Iri, ProjectRef, Caller) => IO[FileResponse] = (id, p, c) => {
    val s = c.subject
    (id, p, s) match {
      case (_, _, `noFileAccess`)      =>
        IO.raiseError(AuthorizationFailed(AclAddress.Project(p), Permission.unsafe("disk/read")))
      case (`fileId`, `projectRef`, _) =>
        IO.pure(
          FileResponse.noCache("file.txt", `text/plain(UTF-8)`, Some(12L), Source.single(ByteString(fileContent)))
        )
      case (id, ref, _)                =>
        IO.raiseError(FileNotFound(id, ref))
    }
  }

  private lazy val routes = {
    for {
      aclCheck       <- AclSimpleCheck(
                          (readAll, AclAddress.Root, perms.toSet),
                          (noFileAccess, AclAddress.Root, perms.toSet)
                        )
      archiveDownload = ArchiveDownload(
                          aclCheck,
                          (id: ResourceRef, ref: ProjectRef) => fetchResource(id.iri, ref),
                          (id: ResourceRef, ref: ProjectRef, c: Caller) => fetchFileContent(id.iri, ref, c),
                          (input: Iri) =>
                            input.toUri match {
                              case Right(`fileSelf`) => IO.pure((projectRef, Latest(fileId)))
                              case _                 => IO.raiseError(InvalidPath(input))
                            }
                        )
      archives        = Archives(fetchContext, archiveDownload, archivesConfig, xas, clock)
      identities      = IdentitiesDummy.fromUsers(readAll, noFileAccess)
      r               = Route.seal(new ArchiveRoutes(archives, identities, aclCheck).routes)
    } yield r
  }.accepted

  private def archiveMetadata(
      id: Iri,
      project: ProjectRef,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = readAll,
      updatedBy: Subject = readAll,
      expiresInSeconds: Long = 18000L
  ): Json          =
    jsonContentOf(
      "responses/archive-metadata-response.json",
      "project"          -> project,
      "id"               -> id,
      "rev"              -> rev,
      "deprecated"       -> deprecated,
      "createdBy"        -> createdBy.asIri,
      "updatedBy"        -> updatedBy.asIri,
      "self"             -> ResourceAccess.ephemeral("archives", project, id).uri,
      "expiresInSeconds" -> expiresInSeconds.toString
    )

  "The ArchiveRoutes" should {

    val archive =
      json"""{
            "resources": [
              {
                "@type": "Resource",
                "resourceId": "$fileId"
              },
              {
                "@type": "File",
                "resourceId": "$fileId"
              },
              {
                "@type": "Resource",
                "resourceId": "$notFoundId"
              },
              {
                "@type": "File",
                "resourceId": "$notFoundId"
              }
            ]
          }"""

    val archiveWithFileSelf =
      json"""{
            "resources": [
              {
                "@type": "Resource",
                "resourceId": "$fileId"
              },
              {
                "@type": "FileSelf",
                "value": "$fileSelf"
              },
              {
                "@type": "Resource",
                "resourceId": "$notFoundId"
              },
              {
                "@type": "FileSelf",
                "value": "$notFoundSelf"
              }
            ]
          }"""

    val archiveCtxJson = jsonContentOf("responses/archive-resource-context.json")

    "create an archive without specifying an id" in {
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> as(readAll) ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(generatedId, project.ref)
      }
    }

    "create an archive without specifying an id and redirect" in {
      uuidF.fixed(UUID.randomUUID()).accepted
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> as(readAll) ~> acceptAll ~> routes ~> check {
        uuidF.fixed(uuid).accepted
        status shouldEqual StatusCodes.SeeOther
        header[Location].value.uri.toString() startsWith baseUri.endpoint.toString() shouldEqual true
      }
    }

    "create an archive with a specific id" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = encodeUriPath(id.toString)
      Put(s"/v1/archives/$projectRef/$encodedId", archive.toEntity) ~> as(readAll) ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(id, project.ref)
      }
    }

    "create an archive with file self" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = encodeUriPath(id.toString)

      Put(s"/v1/archives/$projectRef/$encodedId", archiveWithFileSelf.toEntity) ~> as(
        readAll
      ) ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(id, project.ref)
      }
    }

    "create an archive with a specific id and redirect" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = encodeUriPath(id.toString)
      Put(s"/v1/archives/$projectRef/$encodedId", archive.toEntity) ~> as(readAll) ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.SeeOther
        header[Location].value.uri.toString() shouldEqual s"${baseUri.endpoint}/archives/$projectRef/$encodedId"
      }
    }

    "fetch an archive json representation" in {
      Get(s"/v1/archives/$projectRef/$uuid") ~> as(readAll) ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          archiveMetadata(generatedId, project.ref)
            .deepMerge(archive)
            .deepMerge(archiveCtxJson)
        )
      }
    }

    "fetch a zip archive ignoring not found" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> as(readAll) ~> acceptZip ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        header[`Content-Type`].value.value() shouldEqual `application/zip`.value
        val result = fromZip(responseEntity.dataBytes)(materializer, executor)

        result.keySet shouldEqual Set(
          s"${project.ref}/file/file.txt",
          s"${project.ref}/compacted/${encodeUri(fileId.toString)}.json"
        )

        val expectedContent = fileContent
        val actualContent   = result.entryAsString(s"${project.ref}/file/file.txt")
        actualContent shouldEqual expectedContent

        val expectedMetadata = FilesRoutesSpec
          .fileMetadata(
            projectRef,
            fileId,
            file.value.attributes,
            storageRef,
            createdBy = readAll,
            updatedBy = readAll
          )
        val actualMetadata   = result.entryAsJson(s"${project.ref}/compacted/${encodeUri(fileId.toString)}.json")
        actualMetadata shouldEqual expectedMetadata
      }
    }

    "fail to fetch an archive json representation that doesn't exist" in {
      Get(s"/v1/archives/$projectRef/missing?ignoreNotFound=true") ~> as(readAll) ~> acceptJsonLd ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("responses/archive-not-found.json")
      }
    }

    "fail to download an archive that doesn't exist" in {
      Get(s"/v1/archives/$projectRef/missing?ignoreNotFound=true") ~> as(readAll) ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("responses/archive-not-found.json")
      }
    }

    "fail to fetch an archive json representation when lacking permissions" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> acceptMeta ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to download an archive when lacking permissions" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> acceptAll ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to download an archive when lacking file permissions" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> as(noFileAccess) ~> acceptAll ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to create an archive when lacking permissions" in {
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fail to create an archive with duplicate paths" in {
      val archive =
        json"""{
            "resources": [
              {
                "@type": "Resource",
                "resourceId": "$fileId",
                "path": "/a/b"
              },
              {
                "@type": "File",
                "resourceId": "$fileId",
                "path": "/a/b"
              }
            ]
          }"""
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> as(readAll) ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("responses/duplicate-paths.json")
      }
    }

    "fail to create an archive with incorrect repr" in {
      val archive =
        json"""{
            "resources": [
              {
                "@type": "IncorrectType",
                "resourceId": "$fileId"
              }
            ]
          }"""
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> as(readAll) ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("responses/decoding-failed.json")
      }
    }

    "fail to create an archive when the same id already exists" in {
      Put(s"/v1/archives/$projectRef/$uuid", archive.toEntity) ~> as(readAll) ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("responses/archive-already-exists.json")
      }
    }
  }
}
