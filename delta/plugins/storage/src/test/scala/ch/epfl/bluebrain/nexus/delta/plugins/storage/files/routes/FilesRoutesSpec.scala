package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.actor.typed
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges._
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutesSpec.fileMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts, permissions, FileFixtures, Files, FilesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageRejection, StorageStatEntry, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.AuthTokenProvider
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts, permissions => storagesPermissions, StorageFixtures, Storages, StoragesConfig, StoragesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.utils.{BaseRouteSpec, RouteFixtures}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.bio.IO
import org.scalatest._

class FilesRoutesSpec extends BaseRouteSpec with CancelAfterFailure with StorageFixtures with FileFixtures {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
  val httpClient: HttpClient                           = HttpClient()(httpClientConfig, system, s)
  val authTokenProvider: AuthTokenProvider             = AuthTokenProvider.test
  val remoteDiskStorageClient                          = new RemoteDiskStorageClient(httpClient, authTokenProvider)

  // TODO: sort out how we handle this in tests
  implicit override def rcr: RemoteContextResolution = {
    implicit val cl: ClassLoader = getClass.getClassLoader
    RemoteContextResolution.fixed(
      storageContexts.storages         -> ContextValue.fromFile("/contexts/storages.json").accepted,
      storageContexts.storagesMetadata -> ContextValue.fromFile("/contexts/storages-metadata.json").accepted,
      fileContexts.files               -> ContextValue.fromFile("/contexts/files.json").accepted,
      Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json").accepted,
      Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json").accepted,
      Vocabulary.contexts.tags         -> ContextValue.fromFile("contexts/tags.json").accepted,
      Vocabulary.contexts.search       -> ContextValue.fromFile("contexts/search.json").accepted
    )
  }

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))
  private val identities              = IdentitiesDummy(caller)

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val fetchContext = FetchContextDummy(Map(project.ref -> project.context))

  private val s3Read        = Permission.unsafe("s3/read")
  private val s3Write       = Permission.unsafe("s3/write")
  private val diskRead      = Permission.unsafe("disk/read")
  private val diskWrite     = Permission.unsafe("disk/write")
  override val allowedPerms =
    Seq(
      permissions.read,
      permissions.write,
      storagesPermissions.write,
      events.read,
      s3Read,
      s3Write,
      diskRead,
      diskWrite
    )

  private val stCfg = config.copy(disk = config.disk.copy(defaultMaxFileSize = 1000, allowedVolumes = Set(path)))

  private val storagesStatistics: StoragesStatistics =
    (_, _) => IO.pure { StorageStatEntry(0, 0) }

  private val aclCheck        = AclSimpleCheck().accepted
  lazy val storages: Storages = Storages(
    fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
    new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport())),
    IO.pure(allowedPerms.toSet),
    (_, _) => IO.unit,
    xas,
    StoragesConfig(eventLogConfig, pagination, stCfg),
    ServiceAccount(User("nexus-sa", Label.unsafe("sa")))
  ).accepted
  lazy val files: Files       = Files(
    fetchContext.mapRejection(FileRejection.ProjectContextRejection),
    aclCheck,
    storages,
    storagesStatistics,
    xas,
    config,
    FilesConfig(eventLogConfig),
    remoteDiskStorageClient
  )
  private val groupDirectives =
    DeltaSchemeDirectives(fetchContext, ioFromMap(uuid -> projectRef.organization), ioFromMap(uuid -> projectRef))

  private lazy val routes     =
    Route.seal(FilesRoutes(stCfg, identities, aclCheck, files, groupDirectives, IndexingAction.noop))

  private val diskIdRev = ResourceRef.Revision(dId, 1)
  private val s3IdRev   = ResourceRef.Revision(s3Id, 2)

  "File routes" should {

    "create storages for files" in {
      val defaults = json"""{"maxFileSize": 1000, "volume": "$path"}"""
      val s3Perms  = json"""{"readPermission": "$s3Read", "writePermission": "$s3Write"}"""
      aclCheck
        .append(
          AclAddress.Root,
          Anonymous      -> Set(storagesPermissions.write),
          caller.subject -> Set(storagesPermissions.write)
        )
        .accepted
      storages.create(s3Id, projectRef, diskFieldsJson deepMerge defaults deepMerge s3Perms).accepted
      storages
        .create(dId, projectRef, diskFieldsJson deepMerge defaults deepMerge json"""{"capacity":5000}""")
        .accepted
    }

    "fail to create a file without disk/write permission" in {
      Post("/v1/files/org/proj", entity()) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a file" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(diskWrite), caller.subject -> Set(diskWrite)).accepted
      Post("/v1/files/org/proj", entity()) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val attr = attributes()
        response.asJson shouldEqual fileMetadata(projectRef, generatedId, attr, diskIdRev)
      }
    }

    "fail to create a file link using a storage that does not allow it" in {
      val payload = json"""{"filename": "my.txt", "path": "my/file.txt", "mediaType": "text/plain"}"""
      Put("/v1/files/org/proj/file1", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("files/errors/unsupported-operation.json", "id" -> file1, "storageId" -> dId)
      }
    }

    "fail to create a file without s3/write permission" in {
      Put("/v1/files/org/proj/file1?storage=s3-storage", entity()) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a file with an authenticated user and provided id" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(s3Write), caller.subject -> Set(s3Write)).accepted
      Put("/v1/files/org/proj/file1?storage=s3-storage", entity("file2.txt")) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val attr = attributes("file2.txt")
        response.asJson shouldEqual
          fileMetadata(projectRef, file1, attr, s3IdRev, createdBy = alice, updatedBy = alice)
      }
    }

    "reject the creation of a file which already exists" in {
      Put("/v1/files/org/proj/file1", entity()) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("/files/errors/already-exists.json", "id" -> file1)
      }
    }

    "reject the creation of a file that is too large" in {
      Put("/v1/files/org/proj/file-too-large", randomEntity(filename = "large-file.txt", 1100)) ~> routes ~> check {
        status shouldEqual StatusCodes.PayloadTooLarge
        response.asJson shouldEqual jsonContentOf("/files/errors/file-too-large.json")
      }
    }

    "reject the creation of a file to a storage that does not exist" in {
      Put("/v1/files/org/proj/file2?storage=not-exist", entity()) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storages/errors/not-found.json", "id" -> (nxv + "not-exist"), "proj" -> projectRef)
      }
    }

    "fail to update a file without disk/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(diskWrite)).accepted
      Put(s"/v1/files/org/proj/file1?rev=1", s3FieldsJson.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a file" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(diskWrite)).accepted
      val endpoints = List(
        "/v1/files/org/proj/file1",
        s"/v1/files/org/proj/$file1Encoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        val filename = s"file-idx-$idx.txt"
        Put(s"$endpoint?rev=${idx + 1}", entity(filename)) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          val attr = attributes(filename)
          response.asJson shouldEqual
            fileMetadata(projectRef, file1, attr, diskIdRev, rev = idx + 2, createdBy = alice)
        }
      }
    }

    "fail to update a file link using a storage that does not allow it" in {
      val payload = json"""{"filename": "my.txt", "path": "my/file.txt", "mediaType": "text/plain"}"""
      Put("/v1/files/org/proj/file1?rev=3", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("files/errors/unsupported-operation.json", "id" -> file1, "storageId" -> dId)
      }
    }

    "reject the update of a non-existent file" in {
      Put("/v1/files/org/proj/myid10?rev=1", entity("other.txt")) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/files/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "org/proj")
      }
    }

    "reject the update of a non-existent file storage" in {
      Put("/v1/files/org/proj/file1?rev=3&storage=not-exist", entity("other.txt")) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storages/errors/not-found.json", "id" -> (nxv + "not-exist"), "proj" -> projectRef)
      }
    }

    "reject the update of a file at a non-existent revision" in {
      Put("/v1/files/org/proj/file1?rev=10", entity("other.txt")) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/files/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to deprecate a file without files/write permission" in {
      Delete(s"/v1/files/org/proj/$uuid?rev=1") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a file" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      Delete(s"/v1/files/org/proj/$uuid?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val attr = attributes()
        response.asJson shouldEqual fileMetadata(projectRef, generatedId, attr, diskIdRev, rev = 2, deprecated = true)
      }
    }

    "reject the deprecation of a file without rev" in {
      Delete("/v1/files/org/proj/file1") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated file" in {
      Delete(s"/v1/files/org/proj/$uuid?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/files/errors/file-deprecated.json", "id" -> generatedId)
      }
    }

    "tag a file" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/files/org/proj/file1/tags?rev=3", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val attr = attributes("file-idx-1.txt")
        response.asJson shouldEqual fileMetadata(projectRef, file1, attr, diskIdRev, rev = 4, createdBy = alice)
      }
    }

    "fail to fetch a file without s3/read permission" in {
      forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
        Get(s"/v1/files/org/proj/file1$suffix") ~> Accept(`*/*`) ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "fail to fetch a file when the accept header does not match file media type" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(diskRead, s3Read)).accepted
      forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
        Get(s"/v1/files/org/proj/file1$suffix") ~> Accept(`video/*`) ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotAcceptable
          response.asJson shouldEqual jsonContentOf("errors/content-type.json", "expected" -> "text/plain")
        }
      }
    }

    "fetch a file" in {
      forAll(List(Accept(`*/*`), Accept(`text/*`))) { accept =>
        forAll(
          List("/v1/files/org/proj/file1", "/v1/resources/org/proj/_/file1", "/v1/resources/org/proj/file/file1")
        ) { endpoint =>
          Get(endpoint) ~> accept ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            contentType.value shouldEqual `text/plain(UTF-8)`.value
            val filename64 = "ZmlsZS1pZHgtMS50eHQ=" // file-idx-1.txt
            header("Content-Disposition").value.value() shouldEqual
              s"""attachment; filename="=?UTF-8?B?$filename64?=""""
            response.asString shouldEqual content
          }
        }
      }
    }

    "fetch a file by rev and tag" in {
      val endpoints = List(
        s"/v1/files/$uuid/$uuid/file1",
        s"/v1/resources/$uuid/$uuid/_/file1",
        s"/v1/resources/$uuid/$uuid/file/file1",
        "/v1/files/org/proj/file1",
        "/v1/resources/org/proj/_/file1",
        "/v1/resources/org/proj/file/file1",
        s"/v1/files/org/proj/$file1Encoded",
        s"/v1/resources/org/proj/_/$file1Encoded",
        s"/v1/resources/org/proj/file/$file1Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> Accept(`*/*`) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            contentType.value shouldEqual `text/plain(UTF-8)`.value
            val filename64 = "ZmlsZTIudHh0" // file2.txt
            header("Content-Disposition").value.value() shouldEqual
              s"""attachment; filename="=?UTF-8?B?$filename64?=""""
            response.asString shouldEqual content
          }
        }
      }
    }

    "fail to fetch a file metadata without resources/read permission" in {
      val endpoints =
        List("/v1/files/org/proj/file1", "/v1/files/org/proj/file1/tags", "/v1/resources/org/proj/_/file1/tags")
      forAll(endpoints) { endpoint =>
        forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
          Get(s"$endpoint$suffix") ~> Accept(`application/ld+json`) ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
        }
      }
    }

    "fetch a file metadata" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.read)).accepted
      Get("/v1/files/org/proj/file1") ~> Accept(`application/ld+json`) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val attr = attributes("file-idx-1.txt")
        response.asJson shouldEqual fileMetadata(projectRef, file1, attr, diskIdRev, rev = 4, createdBy = alice)
      }
    }

    "fetch a file metadata by rev and tag" in {
      val attr      = attributes("file2.txt")
      val endpoints = List(
        s"/v1/files/$uuid/$uuid/file1",
        s"/v1/resources/$uuid/$uuid/_/file1",
        s"/v1/resources/$uuid/$uuid/file/file1",
        "/v1/files/org/proj/file1",
        "/v1/resources/org/proj/_/file1",
        s"/v1/files/org/proj/$file1Encoded",
        s"/v1/resources/org/proj/_/$file1Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> Accept(`application/ld+json`) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual
              fileMetadata(projectRef, file1, attr, s3IdRev, createdBy = alice, updatedBy = alice)
          }
        }
      }
    }

    "fetch the file tags" in {
      Get("/v1/resources/org/proj/_/file1/tags?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/files/org/proj/file1/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/files/org/proj/file1?tag=myother") ~> Accept(`application/ld+json`) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/files/org/proj/file1?tag=mytag&rev=1") ~> Accept(`application/ld+json`) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/tag-and-rev-error.json")
      }
    }

    "delete a tag on file" in {
      Delete("/v1/files/org/proj/file1/tags/mytag?rev=4") ~> routes ~> check {
        val attr = attributes("file-idx-1.txt")
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual fileMetadata(projectRef, file1, attr, diskIdRev, rev = 5, createdBy = alice)
      }
    }

    "not return the deleted tag" in {
      Get("/v1/files/org/proj/file1/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
    }

    "fail to fetch file by the deleted tag" in {
      Get("/v1/files/org/proj/file1?tag=mytag") ~> Accept(`application/ld+json`) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "mytag")
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/files/org/project/file1") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri("https://bbp.epfl.ch/nexus/web/org/project/resources/file1")
      }
    }
  }
}

object FilesRoutesSpec extends TestHelpers with RouteFixtures {

  def fileMetadata(
      project: ProjectRef,
      id: Iri,
      attributes: FileAttributes,
      storage: ResourceRef.Revision,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  )(implicit baseUri: BaseUri): Json =
    jsonContentOf(
      "files/file-route-metadata-response.json",
      "project"     -> project,
      "id"          -> id,
      "rev"         -> rev,
      "storage"     -> storage.iri,
      "storageType" -> storageType,
      "storageRev"  -> storage.rev,
      "bytes"       -> attributes.bytes,
      "digest"      -> attributes.digest.asInstanceOf[ComputedDigest].value,
      "algorithm"   -> attributes.digest.asInstanceOf[ComputedDigest].algorithm,
      "filename"    -> attributes.filename,
      "mediaType"   -> attributes.mediaType.fold("")(_.value),
      "origin"      -> attributes.origin,
      "uuid"        -> attributes.uuid,
      "deprecated"  -> deprecated,
      "createdBy"   -> createdBy.asIri,
      "updatedBy"   -> updatedBy.asIri,
      "type"        -> storageType,
      "self"        -> ResourceUris("files", project, id).accessUri
    )

}
