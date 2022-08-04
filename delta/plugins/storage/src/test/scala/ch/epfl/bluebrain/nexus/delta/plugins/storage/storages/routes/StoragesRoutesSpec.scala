package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{ProjectContextRejection, StorageFetchRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatsCollection.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts, _}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.IndexingActionDummy
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.Json
import monix.bio.IO
import org.scalatest._

import java.time.Instant
import java.util.UUID

class StoragesRoutesSpec extends BaseRouteSpec with TryValues with StorageFixtures {

  import akka.actor.typed.scaladsl.adapter._
  implicit private val typedSystem = system.toTyped

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

  private val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val caller     = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))
  private val identities = IdentitiesDummy(caller)

  private val asAlice = addCredentials(OAuth2BearerToken("alice"))

  private val am         = ApiMappings("nxv" -> nxv.base, "storage" -> schemas.storage)
  private val projBase   = nxv.base
  private val project    =
    ProjectGen.project("myorg", "myproject", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)
  private val projectRef = project.ref

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

  private val perms        = allowedPerms.toSet
  private val aclCheck     = AclSimpleCheck().accepted
  private val fetchContext = FetchContextDummy[StorageFetchRejection](
    Map(project.ref -> project.context),
    ProjectContextRejection
  )

  implicit private val c: Crypto = crypto

  private val storageStatistics = StoragesStatisticsSetup.init(
    Map(
      project -> Map(
        dId  -> StorageStatEntry(10L, 1000L, Some(Instant.ofEpochMilli(1000L))),
        rdId -> StorageStatEntry(50L, 5000L, Some(Instant.ofEpochMilli(5000L))),
        s3Id -> StorageStatEntry(100L, 10000L, Some(Instant.ofEpochMilli(10000L)))
      )
    )
  )

  private val cfg = StoragesConfig(eventLogConfig, pagination, config)

  private lazy val storages    = Storages(
    fetchContext,
    new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport())),
    IO.pure(perms),
    (_, _) => IO.unit,
    crypto,
    xas,
    cfg,
    serviceAccount
  ).accepted
  private val schemeDirectives =
    DeltaSchemeDirectives(fetchContext, ioFromMap(uuid -> projectRef.organization), ioFromMap(uuid -> projectRef))

  private lazy val routes      =
    Route.seal(
      StoragesRoutes(cfg, identities, aclCheck, storages, storageStatistics, schemeDirectives, IndexingActionDummy())
    )

  "Storage routes" should {

    "fail to create a storage without storages/write permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      val payload = s3FieldsJson.value deepMerge json"""{"@id": "$s3Id"}"""
      Post("/v1/storages/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "create a storage" in {
      aclCheck
        .append(AclAddress.Root, Anonymous -> Set(permissions.write), caller.subject -> Set(permissions.write))
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
        response.asJson shouldEqual jsonContentOf("/storages/errors/already-exists.json", "id" -> s3Id)
      }
    }

    "fail to update a storage without storages/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      Put(s"/v1/storages/myorg/myproject/s3-storage?rev=1", s3FieldsJson.value.toEntity) ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "update a storage" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
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
          jsonContentOf("/storages/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a storage at a non-existent revision" in {
      Put("/v1/storages/myorg/myproject/s3-storage?rev=10", s3FieldsJson.value.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/storages/errors/incorrect-rev.json", "provided" -> 10L, "expected" -> 4L)
      }
    }

    "fail to deprecate a storage without storages/write permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
      Delete("/v1/storages/myorg/myproject/s3-storage?rev=3") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "deprecate a storage" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.write)).accepted
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
        response.asJson shouldEqual jsonContentOf("/storages/errors/storage-deprecated.json", "id" -> s3Id)
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
      aclCheck.append(AclAddress.Root, Anonymous -> Set(permissions.read)).accepted
      Get("/v1/storages/myorg/myproject/s3-storage") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storages/s3-storage-fetched.json")
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
            response.asJson shouldEqual jsonContentOf("storages/remote-storage-fetched.json")
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
        response.asJson shouldEqual jsonContentOf("storages/storages-list.json")
      }
    }

    "list remote disk storages" in {
      val encodedStorage = UrlUtils.encode(nxvStorage.toString)
      Get(s"/v1/storages/myorg/myproject/caches?type=$encodedStorage&type=nxv:RemoteDiskStorage") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storages/storages-list-not-deprecated.json")
      }
    }

    "list not deprecated storages" in {
      Get("/v1/storages/myorg/myproject/caches?deprecated=false") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storages/storages-list-not-deprecated.json")
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

    "get storage statistics for an existing entry" in {
      Get("/v1/storages/myorg/myproject/remote-disk-storage/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storages/statistics.json")
      }
    }

    "fail to get storage statistics for an unknown storage" in {
      Get("/v1/storages/myorg/myproject/unknown/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "/storages/errors/not-found.json",
          "id"   -> (nxv + "unknown"),
          "proj" -> "myorg/myproject"
        )
      }
    }

    "fail to get storage statistics for an existing entry without resources/read permission" in {
      aclCheck.subtract(AclAddress.Root, Anonymous -> Set(permissions.read)).accepted
      Get("/v1/storages/myorg/myproject/remote-disk-storage/statistics") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      Get("/v1/storages/myorg/myproject/remote-disk-storage") ~> Accept(`text/html`) ~> routes ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual Uri(
          "https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/remote-disk-storage"
        )
      }
    }

  }

  def storageMetadata(
      ref: ProjectRef,
      id: Iri,
      storageType: StorageType,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "storages/storage-route-metadata-response.json",
      "project"    -> ref,
      "id"         -> id,
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.asIri,
      "updatedBy"  -> updatedBy.asIri,
      "type"       -> storageType,
      "algorithm"  -> DigestAlgorithm.default,
      "label"      -> lastSegment(id)
    )
}
