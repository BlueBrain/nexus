package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{permissions, FileFixtures, Files, FilesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig, permissions => storagesPermissions}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure, Inspectors, OptionValues}
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext

class FilesRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with RouteFixtures
    with StorageFixtures
    with ConfigFixtures
    with BeforeAndAfterAll
    with FileFixtures {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem          = system.toTyped
  implicit val ec: ExecutionContext = system.dispatcher

  override protected def createActorSystem(): ActorSystem =
    ActorSystem("FilesRoutersSpec", AbstractDBSpec.config)

  implicit private val subject: Subject = Identity.Anonymous

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val (orgs, projs) =
    ProjectSetup.init(orgsToCreate = List(org), projectsToCreate = List(project)).accepted

  private val s3Read       = Permission.unsafe("s3/read")
  private val s3Write      = Permission.unsafe("s3/write")
  private val diskRead     = Permission.unsafe("disk/read")
  private val diskWrite    = Permission.unsafe("disk/write")
  private val allowedPerms =
    Set(
      permissions.read,
      permissions.write,
      storagesPermissions.write,
      events.read,
      s3Read,
      s3Write,
      diskRead,
      diskWrite
    )

  private val storageConfig = StoragesConfig(
    aggregate,
    keyValueStore,
    pagination,
    indexing,
    config.copy(disk = config.disk.copy(defaultMaxFileSize = 1000, allowedVolumes = Set(path)))
  )
  private val filesConfig   = FilesConfig(aggregate, indexing)

  private val perms           = PermissionsDummy(allowedPerms)
  private val acls            = AclsDummy(perms).accepted
  private val storageEventLog =
    EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors.accepted
  private val fileEventLog    =
    EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors.accepted
  private val storages        =
    Storages(storageConfig, storageEventLog, perms.accepted, orgs, projs, (_, _) => IO.unit).accepted
  private val routes          =
    Route.seal(
      FilesRoutes(
        storageConfig.storageTypeConfig,
        identities,
        acls,
        orgs,
        projs,
        Files(filesConfig, fileEventLog, acls, orgs, projs, storages).accepted
      )
    )

  private val diskIdRev = ResourceRef.Revision(dId, 1)
  private val s3IdRev   = ResourceRef.Revision(s3Id, 2)

  "File routes" should {

    "create storages for files" in {
      val defaults = json"""{"maxFileSize": 1000, "volume": "$path"}"""
      val s3Perms  = json"""{"readPermission": "$s3Read", "writePermission": "$s3Write"}"""
      acls
        .append(
          Acl(
            AclAddress.Root,
            Anonymous      -> Set(storagesPermissions.write),
            caller.subject -> Set(storagesPermissions.write)
          ),
          0
        )
        .accepted
      storages.create(IriSegment(s3Id), projectRef, diskFieldsJson.map(_ deepMerge defaults deepMerge s3Perms)).accepted
      storages.create(IriSegment(dId), projectRef, diskFieldsJson.map(_ deepMerge defaults)).accepted
    }

    "fail to create a file without disk/write permission" in {
      Post("/v1/files/org/proj", entity()) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a file" in {
      acls
        .append(Acl(AclAddress.Root, Anonymous -> Set(diskWrite), caller.subject -> Set(diskWrite)), 1)
        .accepted
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
          jsonContentOf("file/errors/unsupported-operation.json", "id" -> file1, "storage" -> dId)
      }
    }

    "fail to create a file without s3/write permission" in {
      Put("/v1/files/org/proj/file1?storage=s3-storage", entity()) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a file with an authenticated user and provided id" in {
      acls
        .append(Acl(AclAddress.Root, Anonymous -> Set(s3Write), caller.subject -> Set(s3Write)), 2)
        .accepted
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
        response.asJson shouldEqual jsonContentOf("/file/errors/already-exists.json", "id" -> file1)
      }
    }

    "reject the creation of a file to a storage that does not exist" in {
      Put("/v1/files/org/proj/file2?storage=not-exist", entity()) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storage/errors/not-found.json", "id" -> (nxv + "not-exist"), "proj" -> projectRef)
      }
    }

    "fail to update a file without disk/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(diskWrite)), 3).accepted
      Put(s"/v1/files/org/proj/file1?rev=1", s3FieldsJson.value.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a file" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(diskWrite)), 4).accepted
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
            fileMetadata(projectRef, file1, attr, diskIdRev, rev = idx + 2L, createdBy = alice)
        }
      }
    }

    "fail to update a file link using a storage that does not allow it" in {
      val payload = json"""{"filename": "my.txt", "path": "my/file.txt", "mediaType": "text/plain"}"""
      Put("/v1/files/org/proj/file1?rev=3", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("file/errors/unsupported-operation.json", "id" -> file1, "storage" -> dId)
      }
    }

    "reject the update of a non-existent file" in {
      Put("/v1/files/org/proj/myid10?rev=1", entity("other.txt")) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/file/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "org/proj")
      }
    }

    "reject the update of a non-existent file storage" in {
      Put("/v1/files/org/proj/file1?rev=3&storage=not-exist", entity("other.txt")) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storage/errors/not-found.json", "id" -> (nxv + "not-exist"), "proj" -> projectRef)
      }
    }

    "reject the update of a file at a non-existent revision" in {
      Put("/v1/files/org/proj/file1?rev=10", entity("other.txt")) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/file/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to deprecate a file without files/write permission" in {
      Delete(s"/v1/files/org/proj/$uuid?rev=1") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a file" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(permissions.write)), 5).accepted
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
        response.asJson shouldEqual jsonContentOf("/file/errors/file-deprecated.json", "id" -> generatedId)
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
        Get(s"/v1/files/org/proj/file1$suffix") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "fetch a file" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(diskRead, s3Read)), 6).accepted
      forAll(List(Accept(`*/*`), Accept(`text/*`))) { accept =>
        Get("/v1/files/org/proj/file1") ~> accept ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          contentType.value shouldEqual `text/plain(UTF-8)`.value
          val filename64 = "ZmlsZS1pZHgtMS50eHQ=" // file-idx-1.txt
          header("Content-Disposition").value.value() shouldEqual
            s"""attachment; filename="=?UTF-8?B?$filename64?=""""
          response.asString shouldEqual content
        }
      }
    }

    "fetch a file by rev and tag" in {
      val endpoints = List(
        s"/v1/files/$uuid/$uuid/file1",
        "/v1/files/org/proj/file1",
        s"/v1/files/org/proj/$file1Encoded"
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
      val endpoints = List("/v1/files/org/proj/file1", "/v1/files/org/proj/file1/tags")
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
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(permissions.read)), 7).accepted
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
        "/v1/files/org/proj/file1",
        s"/v1/files/org/proj/$file1Encoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> Accept(`application/*`) ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual
              fileMetadata(projectRef, file1, attr, s3IdRev, createdBy = alice, updatedBy = alice)
          }
        }
      }
    }

    "fetch the file tags" in {
      Get("/v1/files/org/proj/file1/tags?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/files/org/proj/file1/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/files/org/proj/file1?tag=myother") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/files/org/proj/file1?tag=mytag&rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/tag-and-rev-error.json")
      }
    }

    "fail to get the events stream without events/read permission" in {
      forAll(List("/v1/files/events", "/v1/files/myorg/events", "/v1/files/org/proj/events")) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

  }

  private var db: JdbcBackend.Database = null

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    db = AbstractDBSpec.beforeAll
    ()
  }

  override protected def afterAll(): Unit = {
    AbstractDBSpec.afterAll(db)
    super.afterAll()
  }
}
