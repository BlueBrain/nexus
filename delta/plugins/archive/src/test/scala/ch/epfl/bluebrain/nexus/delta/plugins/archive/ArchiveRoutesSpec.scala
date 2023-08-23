package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.{`application/x-tar`, `application/zip`}
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{ContentTypes, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encode
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{StatefulUUIDF, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.FileSelf.ParsingError.InvalidPath
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.archive.routes.ArchiveRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{File, FileAttributes, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutesSpec
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileGen}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{DeltaSchemeDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EphemeralLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.archive.ArchiveHelpers
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.TryValues

import java.util.UUID
import scala.concurrent.duration._

class ArchiveRoutesSpec extends BaseRouteSpec with StorageFixtures with TryValues with ArchiveHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  private val uuid                          = UUID.fromString("8249ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidF: StatefulUUIDF = UUIDF.stateful(uuid).accepted

  implicit override def rcr: RemoteContextResolution = RemoteContextResolutionFixture.rcr

  private val subject: Subject            = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller     = Caller.unsafe(subject)
  private val subjectNoFilePerms: Subject = Identity.User("nofileperms", Label.unsafe("realm"))
  private val callerNoFilePerms: Caller   = Caller.unsafe(subjectNoFilePerms)

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
    Permissions.resources.read,
    model.permissions.read,
    model.permissions.write
  )

  private val asSubject     = addCredentials(OAuth2BearerToken("user"))
  private val asNoFilePerms = addCredentials(OAuth2BearerToken("nofileperms"))
  private val acceptMeta    = Accept(`application/ld+json`)
  private val acceptAll     = Accept(`*/*`)

  private val fetchContext    = FetchContextDummy(List(project))
  private val groupDirectives = DeltaSchemeDirectives(fetchContext, _ => UIO.none, _ => UIO.none)

  private val storageRef = ResourceRef.Revision(iri"http://localhost/${genString()}", 5)

  private val fileId                = iri"http://localhost/${genString()}"
  private val encodedFileId         = encode(fileId.toString)
  private val fileSelf              = uri"http://delta:8080/files/$encodedFileId"
  private val fileContent           = "file content"
  private val fileAttributes        = FileAttributes(
    uuid,
    "http://localhost/file.txt",
    Uri.Path("file.txt"),
    "myfile",
    Some(`text/plain(UTF-8)`),
    12L,
    ComputedDigest(DigestAlgorithm.default, "digest"),
    Client
  )
  private val file: ResourceF[File] =
    FileGen.resourceFor(fileId, projectRef, storageRef, fileAttributes, createdBy = subject, updatedBy = subject)

  private val notFoundId   = uri"http://localhost/${genString()}"
  private val notFoundSelf = uri"http://delta:8080/files/${encode(notFoundId.toString)}"

  private val generatedId = project.base.iri / uuid.toString

  val fetchResource: (Iri, ProjectRef) => UIO[Option[JsonLdContent[_, _]]] = {
    case (`fileId`, `projectRef`) =>
      UIO.some(JsonLdContent(file, file.value.asJson, None))
    case _                        =>
      UIO.none
  }

  val fetchFileContent: (Iri, ProjectRef, Caller) => IO[FileRejection, FileResponse] = (id, p, c) => {
    val s = c.subject
    (id, p, s) match {
      case (_, _, `subjectNoFilePerms`) =>
        IO.raiseError(FileRejection.AuthorizationFailed(AclAddress.Project(p), Permission.unsafe("disk/read")))
      case (`fileId`, `projectRef`, _)  =>
        IO.pure(FileResponse("file.txt", ContentTypes.`text/plain(UTF-8)`, 12L, Source.single(ByteString(fileContent))))
      case (id, ref, _)                 =>
        IO.raiseError(FileNotFound(id, ref))
    }
  }

  private lazy val routes = {
    for {
      aclCheck       <- AclSimpleCheck(
                          (subject, AclAddress.Root, perms.toSet),
                          (subjectNoFilePerms, AclAddress.Root, perms.toSet)
                        )
      archiveDownload = ArchiveDownload(
                          aclCheck,
                          (id: ResourceRef, ref: ProjectRef) => fetchResource(id.iri, ref),
                          (id: ResourceRef, ref: ProjectRef, c: Caller) => fetchFileContent(id.iri, ref, c),
                          (input: Iri) =>
                            input.toUri match {
                              case `fileSelf` => IO.pure((projectRef, Latest(fileId)))
                              case _          => IO.raiseError(InvalidPath(input))
                            }
                        )
      archives        = Archives(fetchContext.mapRejection(ProjectContextRejection), archiveDownload, archivesConfig, xas)
      identities      = IdentitiesDummy(caller, callerNoFilePerms)
      r               = Route.seal(new ArchiveRoutes(archives, identities, aclCheck, groupDirectives).routes)
    } yield r
  }.accepted

  private def archiveMetadata(
      id: Iri,
      project: ProjectRef,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = subject,
      updatedBy: Subject = subject,
      expiresInSeconds: Long = 18000L
  ): Json =
    jsonContentOf(
      "responses/archive-metadata-response.json",
      "project"          -> project,
      "id"               -> id,
      "rev"              -> rev,
      "deprecated"       -> deprecated,
      "createdBy"        -> createdBy.asIri,
      "updatedBy"        -> updatedBy.asIri,
      "self"             -> ResourceUris.ephemeral("archives", project, id).accessUri,
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
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> asSubject ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(generatedId, project.ref)
      }
    }

    "create an archive without specifying an id and redirect" in {
      uuidF.fixed(UUID.randomUUID()).accepted
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
        uuidF.fixed(uuid).accepted
        status shouldEqual StatusCodes.SeeOther
        header[Location].value.uri.toString() startsWith baseUri.endpoint.toString() shouldEqual true
      }
    }

    "create an archive with a specific id" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = encode(id.toString).replaceAll("%3A", ":")
      Put(s"/v1/archives/$projectRef/$encodedId", archive.toEntity) ~> asSubject ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(id, project.ref)
      }
    }

    "create an archive with file self" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = encode(id.toString).replaceAll("%3A", ":")

      Put(
        s"/v1/archives/$projectRef/$encodedId",
        archiveWithFileSelf.toEntity
      ) ~> asSubject ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(id, project.ref)
      }
    }

    "create an archive with a specific id and redirect" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = encode(id.toString).replaceAll("%3A", ":")
      Put(s"/v1/archives/$projectRef/$encodedId", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.SeeOther
        header[Location].value.uri.toString() shouldEqual s"${baseUri.endpoint}/archives/$projectRef/$encodedId"
      }
    }

    "fetch an archive json representation" in {
      Get(s"/v1/archives/$projectRef/$uuid") ~> asSubject ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(
          archiveMetadata(generatedId, project.ref)
            .deepMerge(archive)
            .deepMerge(archiveCtxJson)
        )
      }
    }

    "fetch a tar archive ignoring not found" in {
      forAll(List(Accept(`application/x-tar`), acceptAll)) { accept =>
        Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> asSubject ~> accept ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          header[`Content-Type`].value.value() shouldEqual `application/x-tar`.value
          val result = fromTar(responseEntity.dataBytes)

          result.keySet shouldEqual Set(
            s"${project.ref}/file/file.txt",
            s"${project.ref}/compacted/${encode(fileId.toString)}.json"
          )

          val expectedContent = fileContent
          val actualContent   = result.entryAsString(s"${project.ref}/file/file.txt")
          actualContent shouldEqual expectedContent

          val expectedMetadata = FilesRoutesSpec.fileMetadata(
            projectRef,
            fileId,
            file.value.attributes,
            storageRef,
            createdBy = subject,
            updatedBy = subject
          )
          val actualMetadata   = result.entryAsJson(s"${project.ref}/compacted/${encode(fileId.toString)}.json")
          actualMetadata shouldEqual expectedMetadata
        }
      }
    }

    "fetch a zip archive ignoring not found" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> asSubject ~> Accept(
        `application/zip`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        header[`Content-Type`].value.value() shouldEqual `application/zip`.value
        val result = fromZip(responseEntity.dataBytes)

        result.keySet shouldEqual Set(
          s"${project.ref}/file/file.txt",
          s"${project.ref}/compacted/${encode(fileId.toString)}.json"
        )

        val expectedContent = fileContent
        val actualContent   = result.entryAsString(s"${project.ref}/file/file.txt")
        actualContent shouldEqual expectedContent

        val expectedMetadata = FilesRoutesSpec.fileMetadata(
          projectRef,
          fileId,
          file.value.attributes,
          storageRef,
          createdBy = subject,
          updatedBy = subject
        )
        val actualMetadata   = result.entryAsJson(s"${project.ref}/compacted/${encode(fileId.toString)}.json")
        actualMetadata shouldEqual expectedMetadata
      }
    }

    "fail to fetch an archive json representation that doesn't exist" in {
      Get(s"/v1/archives/$projectRef/missing?ignoreNotFound=true") ~> asSubject ~> Accept(
        `application/ld+json`
      ) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("responses/archive-not-found.json")
      }
    }

    "fail to download an archive that doesn't exist" in {
      Get(s"/v1/archives/$projectRef/missing?ignoreNotFound=true") ~> asSubject ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("responses/archive-not-found.json")
      }
    }

    "fail to fetch an archive json representation when lacking permissions" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("responses/authorization-failed.json")
      }
    }

    "fail to download an archive when lacking permissions" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("responses/authorization-failed.json")
      }
    }

    "fail to download an archive when lacking file permissions" in {
      Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> asNoFilePerms ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("responses/file-authorization-failed.json")
      }
    }

    "fail to create an archive when lacking permissions" in {
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("responses/authorization-failed.json")
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
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
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
      Post(s"/v1/archives/$projectRef", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("responses/decoding-failed.json")
      }
    }

    "fail to create an archive when the same id already exists" in {
      Put(s"/v1/archives/$projectRef/$uuid", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("responses/archive-already-exists.json")
      }
    }
  }
}
