package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageStatEntry, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts, _}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceScope
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.Json
import org.scalatest.Assertion

class StoragesRoutesSpec extends BaseRouteSpec with StorageFixtures with UUIDFFixtures.Random {

  // TODO: sort out how we handle this in tests
  implicit override def rcr: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      storageContexts.storages         -> ContextValue.fromFile("contexts/storages.json"),
      storageContexts.storagesMetadata -> ContextValue.fromFile("contexts/storages-metadata.json"),
      fileContexts.files               -> ContextValue.fromFile("contexts/files.json"),
      Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json"),
      Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json"),
      Vocabulary.contexts.search       -> ContextValue.fromFile("contexts/search.json")
    )

  private val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  private val reader = User("reader", realm)
  private val writer = User("writer", realm)

  implicit private val callerReader: Caller =
    Caller(reader, Set(reader, Anonymous, Authenticated(realm), Group("group", realm)))
  implicit private val callerWriter: Caller =
    Caller(writer, Set(writer, Anonymous, Authenticated(realm), Group("group", realm)))
  private val identities                    = IdentitiesDummy(callerReader, callerWriter)

  private val asReader = addCredentials(OAuth2BearerToken("reader"))
  private val asWriter = addCredentials(OAuth2BearerToken("writer"))

  private val am         = ApiMappings("nxv" -> nxv.base, "storage" -> schemas.storage)
  private val projBase   = nxv.base
  private val project    =
    ProjectGen.project("myorg", "myproject", uuid = randomUuid, orgUuid = randomUuid, base = projBase, mappings = am)
  private val projectRef = project.ref

  private val s3IdEncoded = UrlUtils.encode(s3Id.toString)

  private val diskRead  = Permission.unsafe("disk/read")
  private val diskWrite = Permission.unsafe("disk/write")
  private val s3Read    = Permission.unsafe("s3/read")
  private val s3Write   = Permission.unsafe("s3/write")

  override val allowedPerms = Seq(
    permissions.read,
    permissions.write,
    events.read,
    diskRead,
    diskWrite,
    s3Read,
    s3Write
  )

  private val perms        = allowedPerms.toSet
  private val aclCheck     = AclSimpleCheck().accepted
  private val fetchContext = FetchContextDummy(Map(project.ref -> project.context))

  private val storageStatistics: StoragesStatistics =
    (storage, project) =>
      if (project.equals(projectRef) && storage.toString.equals("s3-storage"))
        IO.pure(StorageStatEntry(50, 5000))
      else IO.raiseError(StorageNotFound(iri"https://bluebrain.github.io/nexus/vocabulary/$storage", project))

  private val cfg = StoragesConfig(eventLogConfig, pagination, config)

  private lazy val storages    = Storages(
    fetchContext,
    ResolverContextResolution(rcr),
    IO.pure(perms),
    _ => IO.unit,
    xas,
    cfg,
    serviceAccount,
    clock
  ).accepted
  private val schemeDirectives = DeltaSchemeDirectives(fetchContext)

  private lazy val routes =
    Route.seal(
      StoragesRoutes(identities, aclCheck, storages, storageStatistics, schemeDirectives)
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    val readPermissions  = Set(permissions.read, diskRead, s3Read)
    val writePermissions = Set(permissions.write, diskWrite, s3Write)
    aclCheck.append(AclAddress.Root, reader -> readPermissions).accepted
    aclCheck.append(AclAddress.Root, writer -> writePermissions).accepted
  }

  "Storage routes" should {

    "fail to create a storage without storages/write permission" in {
      aclCheck.append(AclAddress.Root, Anonymous -> Set(events.read)).accepted
      val payload = s3FieldsJson deepMerge json"""{"@id": "$s3Id"}"""
      Post("/v1/storages/myorg/myproject", payload.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a storage" in {
      val payload = s3FieldsJson deepMerge json"""{"@id": "$s3Id", "bucket": "mybucket2"}"""
      Post("/v1/storages/myorg/myproject", payload.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual storageMetadata(projectRef, s3Id, StorageType.S3Storage)
      }
    }

    "reject the creation of a storage which already exists" in {
      Put("/v1/storages/myorg/myproject/s3-storage", s3FieldsJson.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("storages/errors/already-exists.json", "id" -> s3Id)
      }
    }

    "fail to update a storage without storages/write permission" in {
      Put(s"/v1/storages/myorg/myproject/s3-storage?rev=1", s3FieldsJson.toEntity) ~> asReader ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "update a storage" in {
      val endpoints = List(
        "/v1/storages/myorg/myproject/s3-storage",
        s"/v1/storages/myorg/myproject/$s3IdEncoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        Put(s"$endpoint?rev=${idx + 1}", s3FieldsJson.toEntity) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual storageMetadata(projectRef, s3Id, StorageType.S3Storage, rev = idx + 2)
        }
      }
    }

    "reject the update of a non-existent storage" in {
      Put("/v1/storages/myorg/myproject/myid10?rev=1", s3FieldsJson.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("storages/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "myorg/myproject")
      }
    }

    "reject the update of a storage at a non-existent revision" in {
      Put("/v1/storages/myorg/myproject/s3-storage?rev=10", s3FieldsJson.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("storages/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to deprecate a storage without storages/write permission" in {
      Delete("/v1/storages/myorg/myproject/s3-storage?rev=3") ~> asReader ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "deprecate a storage" in {
      Delete("/v1/storages/myorg/myproject/s3-storage?rev=3") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual
          storageMetadata(
            projectRef,
            s3Id,
            StorageType.S3Storage,
            rev = 4,
            deprecated = true,
            updatedBy = writer,
            createdBy = writer
          )
      }
    }

    "reject the deprecation of a storage without rev" in {
      Delete("/v1/storages/myorg/myproject/myid") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of a already deprecated storage" in {
      Delete(s"/v1/storages/myorg/myproject/s3-storage?rev=4") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("storages/errors/storage-deprecated.json", "id" -> s3Id)
      }
    }

    "fail to undeprecate a storage without storages/write permission" in {
      givenADeprecatedStorage { storage =>
        Put(s"/v1/storages/myorg/myproject/$storage/undeprecate?rev=2") ~> asReader ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "undeprecate a deprecated storage" in {
      givenADeprecatedStorage { storage =>
        Put(s"/v1/storages/myorg/myproject/$storage/undeprecate?rev=2") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            storageMetadata(
              projectRef,
              nxv + storage,
              StorageType.DiskStorage,
              rev = 3,
              deprecated = false,
              updatedBy = writer,
              createdBy = writer
            )
        }
      }
    }

    "reject the undeprecation of a storage without rev" in {
      givenADeprecatedStorage { storage =>
        Put(s"/v1/storages/myorg/myproject/$storage/undeprecate") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("errors/missing-query-param.json", "field" -> "rev")
        }
      }
    }

    "reject the undeprecation of a storage that is not deprecated" in {
      givenAStorage { storage =>
        Put(s"/v1/storages/myorg/myproject/$storage/undeprecate?rev=1") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf(
            "storages/errors/storage-not-deprecated.json",
            "id" -> (nxv + storage)
          )
        }
      }
    }

    "fail to fetch a storage and do listings without resources/read permission" in {
      Get(s"/v1/storages/myorg/myproject/s3-storage") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "fetch a storage" in {
      Get("/v1/storages/myorg/myproject/s3-storage") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf(
          "storages/s3-storage-fetched.json",
          "self" -> self(s3Id)
        )
      }
    }

    "fetch a storage by rev" in {
      val endpoints = List(
        "/v1/storages/myorg/myproject/s3-storage",
        "/v1/resources/myorg/myproject/_/s3-storage",
        "/v1/resources/myorg/myproject/storage/s3-storage",
        s"/v1/storages/myorg/myproject/$s3IdEncoded",
        s"/v1/resources/myorg/myproject/_/$s3IdEncoded",
        s"/v1/resources/myorg/myproject/storage/$s3IdEncoded"
      )
      forAll(endpoints) { endpoint =>
        Get(s"$endpoint?rev=4") ~> asReader ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual jsonContentOf(
            "storages/s3-storage-fetched.json",
            "self" -> self(s3Id)
          )
        }
      }
    }

    "fetch a storage original payload" in {
      val expectedSource = s3FieldsJson
      val endpoints      = List(
        "/v1/storages/myorg/myproject/s3-storage/source",
        "/v1/resources/myorg/myproject/_/s3-storage/source",
        s"/v1/storages/myorg/myproject/$s3IdEncoded/source",
        s"/v1/resources/myorg/myproject/_/$s3IdEncoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asReader ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual expectedSource
        }
      }
    }

    "fetch a storage original payload by rev" in {
      val endpoints = List(
        "/v1/storages/myorg/myproject/s3-storage/source",
        s"/v1/storages/myorg/myproject/$s3IdEncoded/source"
      )
      forAll(endpoints) { endpoint =>
        Get(s"$endpoint?rev=4") ~> asReader ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual s3FieldsJson
        }
      }
    }

    "get storage statistics for an existing entry" in {
      Get("/v1/storages/myorg/myproject/s3-storage/statistics") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual jsonContentOf("storages/statistics.json")
      }
    }

    "fail to get storage statistics for an unknown storage" in {
      Get("/v1/storages/myorg/myproject/unknown/statistics") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf(
          "storages/errors/not-found.json",
          "id"   -> (nxv + "unknown"),
          "proj" -> "myorg/myproject"
        )
      }
    }

    "fail to get storage statistics for an existing entry without resources/read permission" in {
      givenAStorage { storage =>
        Get(s"/v1/storages/myorg/myproject/$storage/statistics") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "redirect to fusion for the latest version if the Accept header is set to text/html" in {
      givenAStorage { storage =>
        Get(s"/v1/storages/myorg/myproject/$storage") ~> asReader ~> Accept(`text/html`) ~> routes ~> check {
          response.status shouldEqual StatusCodes.SeeOther
          response.header[Location].value.uri shouldEqual Uri(
            s"https://bbp.epfl.ch/nexus/web/myorg/myproject/resources/$storage"
          )
        }
      }
    }

    def givenAStorage(test: String => Assertion): Assertion = {
      val id = genString()
      Put(s"/v1/storages/myorg/myproject/$id", diskFieldsJson.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
      }
      test(id)
    }

    def givenADeprecatedStorage(test: String => Assertion): Assertion = {
      givenAStorage { id =>
        Delete(s"/v1/storages/myorg/myproject/$id?rev=1") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
        }
        test(id)
      }
    }

  }

  def storageMetadata(
      ref: ProjectRef,
      id: Iri,
      storageType: StorageType,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = writer,
      updatedBy: Subject = writer
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
      "self"       -> self(id)
    )

  def self(id: Iri): Uri = ResourceScope("storages", projectRef, id).accessUri
}
