package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import slick.jdbc.JdbcBackend

import java.time.Instant
import java.util.UUID

class StoragesRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TryValues
    with TestMatchers
    with Inspectors
    with CancelAfterFailure
    with RouteFixtures
    with StorageFixtures
    with ConfigFixtures
    with BeforeAndAfterAll {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = system.toTyped

  override protected def createActorSystem(): ActorSystem =
    ActorSystem("StoragesRoutesSpec", AbstractDBSpec.config)

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val subject: Subject = Identity.Anonymous

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val org        = Label.unsafe("myorg")
  private val am         = ApiMappings("nxv" -> nxv.base, "storage" -> schemas.storage)
  private val projBase   = nxv.base
  private val project    = ProjectGen.resourceFor(
    ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  )
  private val projectRef = project.value.ref

  private val remoteIdEncoded = UrlUtils.encode(rdId.toString)
  private val s3IdEncoded     = UrlUtils.encode(s3Id.toString)

  override val allowedPerms = Seq(
    permissions.read,
    permissions.write,
    events.read,
    Permission.unsafe("s3/read"),
    Permission.unsafe("s3/write"),
    Permission.unsafe("remote/read"),
    Permission.unsafe("remote/write")
  )

  private val cfg = StoragesConfig(aggregate, keyValueStore, pagination, indexing, persist, config)

  private val perms         = PermissionsDummy(allowedPerms.toSet).accepted
  private val acls          = AclsDummy(perms, RealmSetup.init(realm).accepted).accepted
  private val (orgs, projs) = ProjectSetup.init(org :: Nil, project.value :: Nil).accepted
  implicit private val c    = crypto

  private val storageStatistics = StoragesStatisticsSetup.init(
    Map(
      project.value -> Map(
        dId  -> StorageStatEntry(10L, 1000L, Instant.ofEpochMilli(1000L)),
        rdId -> StorageStatEntry(50L, 5000L, Instant.ofEpochMilli(5000L)),
        s3Id -> StorageStatEntry(100L, 10000L, Instant.ofEpochMilli(10000L))
      )
    )
  )

  private val storages = StoragesSetup.init(orgs, projs, perms)
  private val routes   =
    Route.seal(StoragesRoutes(cfg, identities, acls, orgs, projs, storages, storageStatistics, IndexingActionDummy()))

  "Storage routes" should {

    "fail to create a storage without storages/write permission" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 0L).accepted
      val payload = s3FieldsJson.value deepMerge json"""{"@id": "$s3Id"}"""
      Post("/v1/storages/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a storage" in {
      acls
        .append(Acl(AclAddress.Root, Anonymous -> Set(permissions.write), caller.subject -> Set(permissions.write)), 1L)
        .accepted
      val payload = s3FieldsJson.value deepMerge json"""{"@id": "$s3Id", "bucket": "mybucket2"}"""
      Post("/v1/storages/myorg/myproject", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual storageMetadata(projectRef, s3Id, StorageType.S3Storage)
      }
    }

    "create a storage with an authenticated user and provided id" in {
      Put(
        "/v1/storages/myorg/myproject/remote-disk-storage",
        remoteFieldsJson.value.toEntity
      ) ~> asAlice ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual
          storageMetadata(projectRef, rdId, StorageType.RemoteDiskStorage, createdBy = alice, updatedBy = alice)
      }
    }

    "reject the creation of a storage which already exists" in {
      Put("/v1/storages/myorg/myproject/s3-storage", s3FieldsJson.value.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("/storage/errors/already-exists.json", "id" -> s3Id)
      }
    }

    "fail to update a storage without storages/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(permissions.write)), 2L).accepted
      Put(s"/v1/storages/myorg/myproject/s3-storage?rev=1", s3FieldsJson.value.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a storage" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(permissions.write)), 3L).accepted
      val endpoints = List(
        "/v1/storages/myorg/myproject/s3-storage",
        s"/v1/storages/myorg/myproject/$s3IdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        // the starting revision is 2 because this storage has been updated to default = false
        Put(s"$endpoint?rev=${idx + 2}", s3FieldsJson.value.toEntity) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual storageMetadata(projectRef, s3Id, StorageType.S3Storage, rev = idx + 3L)
        }
      }
    }

    "reject the update of a non-existent storage" in {
      Put("/v1/storages/myorg/myproject/myid10?rev=1", s3FieldsJson.value.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storage/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a storage at a non-existent revision" in {
      Put("/v1/storages/myorg/myproject/s3-storage?rev=10", s3FieldsJson.value.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/storage/errors/incorrect-rev.json", "provided" -> 10L, "expected" -> 4L)
      }
    }

    "fail to deprecate a storage without storages/write permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(permissions.write)), 4L).accepted
      Delete("/v1/storages/myorg/myproject/s3-storage?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a storage" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(permissions.write)), 5L).accepted
      Delete("/v1/storages/myorg/myproject/s3-storage?rev=4") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          storageMetadata(projectRef, s3Id, StorageType.S3Storage, rev = 5, deprecated = true)
      }
    }

    "reject the deprecation of a storage without rev" in {
      Delete("/v1/storages/myorg/myproject/myid") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated storage" in {
      Delete(s"/v1/storages/myorg/myproject/s3-storage?rev=5") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/storage/errors/storage-deprecated.json", "id" -> s3Id)
      }
    }

    "tag a storage" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      // the revision is 2 because this storage has been updated to default = false
      Post("/v1/storages/myorg/myproject/remote-disk-storage/tags?rev=2", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual
          storageMetadata(projectRef, rdId, StorageType.RemoteDiskStorage, rev = 3, createdBy = alice)
      }
    }

    "fail to fetch a storage and do listings without resources/read permission" in {
      val endpoints = List(
        "/v1/storages/myorg/myproject/caches",
        "/v1/storages/myorg/myproject/remote-disk-storage",
        "/v1/storages/myorg/myproject/remote-disk-storage/tags"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
          Get(s"$endpoint$suffix") ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
        }
      }
    }

    "fetch a storage" in {
      acls.append(Acl(AclAddress.Root, Anonymous -> Set(permissions.read)), 6L).accepted
      Get("/v1/storages/myorg/myproject/s3-storage") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storage/s3-storage-fetched.json")
      }
    }

    "fetch a storage by rev and tag" in {
      val endpoints = List(
        s"/v1/storages/$uuid/$uuid/remote-disk-storage",
        s"/v1/resources/$uuid/$uuid/_/remote-disk-storage",
        s"/v1/resources/$uuid/$uuid/storage/remote-disk-storage",
        "/v1/storages/myorg/myproject/remote-disk-storage",
        "/v1/resources/myorg/myproject/_/remote-disk-storage",
        "/v1/resources/myorg/myproject/storage/remote-disk-storage",
        s"/v1/storages/myorg/myproject/$remoteIdEncoded",
        s"/v1/resources/myorg/myproject/_/$remoteIdEncoded",
        s"/v1/resources/myorg/myproject/storage/$remoteIdEncoded"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual jsonContentOf("storage/remote-storage-fetched.json")
          }
        }
      }
    }

    "fetch a storage original payload" in {
      val expectedSource = remoteFieldsJson.map(_ deepMerge json"""{"default": false}""")
      val endpoints      = List(
        s"/v1/storages/$uuid/$uuid/remote-disk-storage/source",
        s"/v1/resources/$uuid/$uuid/_/remote-disk-storage/source",
        s"/v1/resources/$uuid/$uuid/storage/remote-disk-storage/source",
        "/v1/storages/myorg/myproject/remote-disk-storage/source",
        "/v1/resources/myorg/myproject/_/remote-disk-storage/source",
        s"/v1/storages/myorg/myproject/$remoteIdEncoded/source",
        s"/v1/resources/myorg/myproject/_/$remoteIdEncoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual Storage.encryptSource(expectedSource, crypto).success.value
        }
      }
    }

    "fetch a storage original payload by rev or tag" in {
      val endpoints = List(
        s"/v1/storages/$uuid/$uuid/remote-disk-storage/source",
        "/v1/storages/myorg/myproject/remote-disk-storage/source",
        s"/v1/storages/myorg/myproject/$remoteIdEncoded/source"
      )
      forAll(endpoints) { endpoint =>
        forAll(List("rev=1", "tag=mytag")) { param =>
          Get(s"$endpoint?$param") ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual Storage.encryptSource(remoteFieldsJson, crypto).success.value
          }
        }
      }
    }

    "list storages" in {
      Get("/v1/storages/myorg/myproject/caches") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storage/storages-list.json")
      }
    }

    "list remote disk storages" in {
      val encodedStorage = UrlUtils.encode(nxvStorage.toString)
      Get(s"/v1/storages/myorg/myproject/caches?type=$encodedStorage&type=nxv:RemoteDiskStorage") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storage/storages-list-not-deprecated.json")
      }
    }

    "list not deprecated storages" in {
      Get("/v1/storages/myorg/myproject/caches?deprecated=false") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storage/storages-list-not-deprecated.json")
      }
    }

    "fetch the storage tags" in {
      Get("/v1/resources/myorg/myproject/_/remote-disk-storage/tags?rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/storages/myorg/myproject/remote-disk-storage/tags") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/storages/myorg/myproject/remote-disk-storage?tag=myother") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/storages/myorg/myproject/remote-disk-storage?tag=mytag&rev=1") ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/tag-and-rev-error.json")
      }
    }

    "fail to get the events stream without events/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(events.read)), 7L).accepted
      forAll(List("/v1/storages/events", "/v1/storages/myorg/events", "/v1/storages/myorg/myproject/events")) {
        endpoint =>
          Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
            response.status shouldEqual StatusCodes.Forbidden
            response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
          }
      }
    }

    "get storage statistics for an existing entry" in {
      Get("/v1/storages/myorg/myproject/remote-disk-storage/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storage/statistics.json")
      }
    }

    "fail to get storage statistics for an unknown storage" in {
      Get("/v1/storages/myorg/myproject/unknown/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "/storage/errors/not-found.json",
          "id"   -> (nxv + "unknown"),
          "proj" -> "myorg/myproject"
        )
      }
    }

    "fail to get storage statistics for an existing entry without resources/read permission" in {
      acls.subtract(Acl(AclAddress.Root, Anonymous -> Set(permissions.read)), 8L).accepted
      Get("/v1/storages/myorg/myproject/remote-disk-storage/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
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
