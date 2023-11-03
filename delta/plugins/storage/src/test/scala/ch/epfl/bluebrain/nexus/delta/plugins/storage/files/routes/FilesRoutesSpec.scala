package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.actor.typed
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges._
import akka.http.scaladsl.model.MediaTypes.`text/html`
import akka.http.scaladsl.model.headers.{Accept, Location, OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileId, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts, permissions, FileFixtures, Files, FilesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{StorageRejection, StorageStatEntry, StorageType}
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
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.{Identities, IdentitiesDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.bio.IOFromMap
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import io.circe.Json
import org.scalatest._

class FilesRoutesSpec
    extends BaseRouteSpec
    with CancelAfterFailure
    with StorageFixtures
    with FileFixtures
    with IOFromMap
    with CatsIOValues {

  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
  val httpClient: HttpClient                           = HttpClient()(httpClientConfig, system, s)
  val authTokenProvider: AuthTokenProvider             = AuthTokenProvider.anonymousForTest
  val remoteDiskStorageClient                          = new RemoteDiskStorageClient(httpClient, authTokenProvider, Credentials.Anonymous)

  // TODO: sort out how we handle this in tests
  implicit override def rcr: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      storageContexts.storages         -> ContextValue.fromFile("/contexts/storages.json"),
      storageContexts.storagesMetadata -> ContextValue.fromFile("/contexts/storages-metadata.json"),
      fileContexts.files               -> ContextValue.fromFile("/contexts/files.json"),
      Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json"),
      Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json"),
      Vocabulary.contexts.tags         -> ContextValue.fromFile("contexts/tags.json"),
      Vocabulary.contexts.search       -> ContextValue.fromFile("contexts/search.json")
    )

  private val reader   = User("reader", realm)
  private val writer   = User("writer", realm)
  private val s3writer = User("s3writer", realm)

  implicit private val callerReader: Caller   =
    Caller(reader, Set(reader, Anonymous, Authenticated(realm), Group("group", realm)))
  implicit private val callerWriter: Caller   =
    Caller(writer, Set(writer, Anonymous, Authenticated(realm), Group("group", realm)))
  implicit private val callerS3Writer: Caller =
    Caller(s3writer, Set(s3writer, Anonymous, Authenticated(realm), Group("group", realm)))
  private val identities                      = IdentitiesDummy(callerReader, callerWriter, callerS3Writer)

  private val asReader   = addCredentials(OAuth2BearerToken("reader"))
  private val asWriter   = addCredentials(OAuth2BearerToken("writer"))
  private val asS3Writer = addCredentials(OAuth2BearerToken("s3writer"))

  private val fetchContext = FetchContextDummy(Map(project.ref -> project.context))

  private val s3Read    = Permission.unsafe("s3/read")
  private val s3Write   = Permission.unsafe("s3/write")
  private val diskRead  = Permission.unsafe("disk/read")
  private val diskWrite = Permission.unsafe("disk/write")

  override val allowedPerms: Seq[Permission] =
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

  private val aclCheck = AclSimpleCheck().accepted

  lazy val storages: Storages                              = Storages(
    fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
    ResolverContextResolution(rcr),
    IO.pure(allowedPerms.toSet),
    (_, _) => IO.unit,
    xas,
    StoragesConfig(eventLogConfig, pagination, stCfg),
    ServiceAccount(User("nexus-sa", Label.unsafe("sa")))
  ).accepted
  lazy val files: Files                                    =
    Files(
      fetchContext.mapRejection(FileRejection.ProjectContextRejection),
      aclCheck,
      storages,
      storagesStatistics,
      xas,
      config,
      FilesConfig(eventLogConfig, MediaTypeDetectorConfig.Empty),
      remoteDiskStorageClient
    )(ceClock, uuidF, timer, contextShift, typedSystem)
  private val groupDirectives                              =
    DeltaSchemeDirectives(fetchContext, ioFromMap(uuid -> projectRef.organization), ioFromMap(uuid -> projectRef))

  private lazy val routes                                  = routesWithIdentities(identities)
  private def routesWithIdentities(identities: Identities) =
    Route.seal(FilesRoutes(stCfg, identities, aclCheck, files, groupDirectives, IndexingAction.noop))

  private val diskIdRev = ResourceRef.Revision(dId, 1)
  private val s3IdRev   = ResourceRef.Revision(s3Id, 2)
  private val tag       = UserTag.unsafe("mytag")

  private val varyHeader = RawHeader("Vary", "Accept,Accept-Encoding")

  override def beforeAll(): Unit = {
    super.beforeAll()

    val writePermissions = Set(storagesPermissions.write, diskWrite, permissions.write)
    val readPermissions  = Set(diskRead, s3Read, permissions.read)
    aclCheck.append(AclAddress.Root, writer -> writePermissions, writer -> readPermissions).accepted
    aclCheck.append(AclAddress.Root, callerWriter.subject -> writePermissions).accepted
    aclCheck.append(AclAddress.Root, reader -> readPermissions).accepted
    aclCheck.append(AclAddress.Root, s3writer -> Set(s3Write), callerS3Writer.subject -> Set(s3Write)).accepted

    val defaults         = json"""{"maxFileSize": 1000, "volume": "$path"}"""
    val s3Perms          = json"""{"readPermission": "$s3Read", "writePermission": "$s3Write"}"""
    storages.create(s3Id, projectRef, diskFieldsJson deepMerge defaults deepMerge s3Perms)(callerWriter).accepted
    storages
      .create(dId, projectRef, diskFieldsJson deepMerge defaults deepMerge json"""{"capacity":5000}""")(callerWriter)
      .void
      .accepted
  }

  "File routes" should {

    "fail to create a file without disk/write permission" in {
      Post("/v1/files/org/proj", entity()) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a file" in {
      Post("/v1/files/org/proj", entity()) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val attr = attributes()
        response.asJson shouldEqual fileMetadata(projectRef, generatedId, attr, diskIdRev)
      }
    }

    "create and tag a file" in {
      withUUIDF(uuid2) {
        Post("/v1/files/org/proj?tag=mytag", entity()) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          val attr      = attributes(id = uuid2)
          val expected  = fileMetadata(projectRef, generatedId2, attr, diskIdRev)
          val fileByTag = files.fetch(FileId(generatedId2, tag, projectRef)).accepted
          response.asJson shouldEqual expected
          fileByTag.value.tags.tags should contain(tag)
        }
      }
    }

    "fail to create a file link using a storage that does not allow it" in {
      val payload = json"""{"filename": "my.txt", "path": "my/file.txt", "mediaType": "text/plain"}"""
      Put("/v1/files/org/proj/file1", payload.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("files/errors/unsupported-operation.json", "id" -> file1, "storageId" -> dId)
      }
    }

    "fail to create a file without s3/write permission" in {
      Put("/v1/files/org/proj/file1?storage=s3-storage", entity()) ~> asWriter ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "create a file on s3 with an authenticated user and provided id" in {
      Put("/v1/files/org/proj/file1?storage=s3-storage", entity("file2.txt")) ~> asS3Writer ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val attr = attributes("file2.txt")
        response.asJson shouldEqual
          fileMetadata(projectRef, file1, attr, s3IdRev, createdBy = s3writer, updatedBy = s3writer)
      }
    }

    "create and tag a file on s3 with an authenticated user and provided id" in {
      withUUIDF(uuid2) {
        Put(
          "/v1/files/org/proj/fileTagged?storage=s3-storage&tag=mytag",
          entity("fileTagged.txt")
        ) ~> asS3Writer ~> routes ~> check {
          status shouldEqual StatusCodes.Created
          val attr      = attributes("fileTagged.txt", id = uuid2)
          val expected  = fileMetadata(projectRef, fileTagged, attr, s3IdRev, createdBy = s3writer, updatedBy = s3writer)
          val fileByTag = files.fetch(FileId(generatedId2, tag, projectRef)).accepted
          response.asJson shouldEqual expected
          fileByTag.value.tags.tags should contain(tag)
        }
      }
    }

    "reject the creation of a file which already exists" in {
      Put("/v1/files/org/proj/file1", entity()) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual jsonContentOf("/files/errors/already-exists.json", "id" -> file1)
      }
    }

    "reject the creation of a file that is too large" in {
      Put(
        "/v1/files/org/proj/file-too-large",
        randomEntity(filename = "large-file.txt", 1100)
      ) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.PayloadTooLarge
        response.asJson shouldEqual jsonContentOf("/files/errors/file-too-large.json")
      }
    }

    "reject the creation of a file to a storage that does not exist" in {
      Put("/v1/files/org/proj/file2?storage=not-exist", entity()) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storages/errors/not-found.json", "id" -> (nxv + "not-exist"), "proj" -> projectRef)
      }
    }

    "fail to update a file without disk/write permission" in {
      Put(s"/v1/files/org/proj/file1?rev=1", s3FieldsJson.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "update a file" in {
      val endpoints = List(
        "/v1/files/org/proj/file1",
        s"/v1/files/org/proj/$file1Encoded"
      )
      forAll(endpoints.zipWithIndex) { case (endpoint, idx) =>
        val filename = s"file-idx-$idx.txt"
        Put(s"$endpoint?rev=${idx + 1}", entity(filename)) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          val attr = attributes(filename)
          response.asJson shouldEqual
            fileMetadata(projectRef, file1, attr, diskIdRev, rev = idx + 2, createdBy = s3writer)
        }
      }
    }

    "update and tag a file in one request" in {
      givenAFile { id =>
        Put(s"/v1/files/org/proj/$id?rev=1&tag=mytag", entity(s"$id.txt")) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
        }
        Get(s"/v1/files/org/proj/$id?tag=mytag") ~> Accept(`*/*`) ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "fail to update a file link using a storage that does not allow it" in {
      val payload = json"""{"filename": "my.txt", "path": "my/file.txt", "mediaType": "text/plain"}"""
      Put("/v1/files/org/proj/file1?rev=3", payload.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual
          jsonContentOf("files/errors/unsupported-operation.json", "id" -> file1, "storageId" -> dId)
      }
    }

    "reject the update of a non-existent file" in {
      Put("/v1/files/org/proj/myid10?rev=1", entity("other.txt")) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/files/errors/not-found.json", "id" -> (nxv + "myid10"), "proj" -> "org/proj")
      }
    }

    "reject the update of a non-existent file storage" in {
      Put("/v1/files/org/proj/file1?rev=3&storage=not-exist", entity("other.txt")) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          jsonContentOf("/storages/errors/not-found.json", "id" -> (nxv + "not-exist"), "proj" -> projectRef)
      }
    }

    "reject the update of a file at a non-existent revision" in {
      Put("/v1/files/org/proj/file1?rev=10", entity("other.txt")) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Conflict
        response.asJson shouldEqual
          jsonContentOf("/files/errors/incorrect-rev.json", "provided" -> 10, "expected" -> 3)
      }
    }

    "fail to deprecate a file without files/write permission" in {
      Delete(s"/v1/files/org/proj/$uuid?rev=1") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }

    "deprecate a file" in {
      Delete(s"/v1/files/org/proj/$uuid?rev=1") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val attr = attributes()
        response.asJson shouldEqual fileMetadata(projectRef, generatedId, attr, diskIdRev, rev = 2, deprecated = true)
      }
    }

    "reject the deprecation of a file without rev" in {
      Delete("/v1/files/org/proj/file1") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
      }
    }

    "reject the deprecation of an already deprecated file" in {
      Delete(s"/v1/files/org/proj/$uuid?rev=2") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/files/errors/file-deprecated.json", "id" -> generatedId)
      }
    }

    "fail to undeprecate a file without files/write permission" in {
      givenADeprecatedFile { id =>
        Put(s"/v1/files/org/proj/$id/undeprecate?rev=2") ~> asReader ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "undeprecate a file" in {
      givenADeprecatedFile { id =>
        Put(s"/v1/files/org/proj/$id/undeprecate?rev=2") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          response.asJson shouldEqual
            fileMetadata(projectRef, nxv + id, attributes(id), diskIdRev, rev = 3, deprecated = false)

          Get(s"/v1/files/org/proj/$id") ~> Accept(`*/*`) ~> asReader ~> routes ~> check {
            status shouldEqual StatusCodes.OK
          }
        }
      }
    }

    "reject the undeprecation of a file without rev" in {
      givenADeprecatedFile { id =>
        Put(s"/v1/files/org/proj/$id/undeprecate") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("/errors/missing-query-param.json", "field" -> "rev")
        }
      }
    }

    "reject the undeprecation of a file that is not deprecated" in {
      givenAFile { id =>
        Put(s"/v1/files/org/proj/$id/undeprecate?rev=1") ~> asWriter ~> routes ~> check {
          status shouldEqual StatusCodes.BadRequest
          response.asJson shouldEqual jsonContentOf("/errors/file-is-not-deprecated.json", "id" -> (nxv + id))
        }
      }
    }

    "tag a file" in {
      val payload = json"""{"tag": "mytag", "rev": 1}"""
      Post("/v1/files/org/proj/file1/tags?rev=3", payload.toEntity) ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        val attr = attributes("file-idx-1.txt")
        response.asJson shouldEqual fileMetadata(projectRef, file1, attr, diskIdRev, rev = 4, createdBy = s3writer)
      }
    }

    "fail to fetch a file without s3/read permission" in {
      forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
        Get(s"/v1/files/org/proj/file1$suffix") ~> Accept(`*/*`) ~> routes ~> check {
          response.shouldBeForbidden
          response.headers should not contain varyHeader
        }
      }
    }

    "fail to fetch a file when the accept header does not match file media type" in {
      forAll(List("", "?rev=1", "?tags=mytag")) { suffix =>
        Get(s"/v1/files/org/proj/file1$suffix") ~> Accept(`video/*`) ~> asReader ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotAcceptable
          response.asJson shouldEqual jsonContentOf("errors/content-type.json", "expected" -> "text/plain")
          response.headers should not contain varyHeader
        }
      }
    }

    "fetch a file" in {
      forAll(List(Accept(`*/*`), Accept(`text/*`))) { accept =>
        forAll(
          List("/v1/files/org/proj/file1", "/v1/resources/org/proj/_/file1", "/v1/resources/org/proj/file/file1")
        ) { endpoint =>
          Get(endpoint) ~> accept ~> asReader ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            contentType.value shouldEqual `text/plain(UTF-8)`.value
            val filename64 = "ZmlsZS1pZHgtMS50eHQ=" // file-idx-1.txt
            header("Content-Disposition").value.value() shouldEqual
              s"""attachment; filename="=?UTF-8?B?$filename64?=""""
            response.asString shouldEqual content
            response.headers should contain(varyHeader)
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
          Get(s"$endpoint?$param") ~> Accept(`*/*`) ~> asReader ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            contentType.value shouldEqual `text/plain(UTF-8)`.value
            val filename64 = "ZmlsZTIudHh0" // file2.txt
            header("Content-Disposition").value.value() shouldEqual
              s"""attachment; filename="=?UTF-8?B?$filename64?=""""
            response.asString shouldEqual content
            response.headers should contain(varyHeader)
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
            response.shouldBeForbidden
            response.headers should not contain varyHeader
          }
        }
      }
    }

    "fetch a file metadata" in {
      Get("/v1/files/org/proj/file1") ~> Accept(`application/ld+json`) ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        val attr = attributes("file-idx-1.txt")
        response.asJson shouldEqual fileMetadata(projectRef, file1, attr, diskIdRev, rev = 4, createdBy = s3writer)
        response.headers should contain(varyHeader)
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
          Get(s"$endpoint?$param") ~> Accept(`application/ld+json`) ~> asReader ~> routes ~> check {
            status shouldEqual StatusCodes.OK
            response.asJson shouldEqual
              fileMetadata(projectRef, file1, attr, s3IdRev, createdBy = s3writer, updatedBy = s3writer)
            response.headers should contain(varyHeader)
          }
        }
      }
    }

    "fetch the file tags" in {
      Get("/v1/resources/org/proj/_/file1/tags?rev=1") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
      Get("/v1/files/org/proj/file1/tags") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": [{"rev": 1, "tag": "mytag"}]}""".addContext(contexts.tags)
      }
    }

    "return not found if tag not found" in {
      Get("/v1/files/org/proj/file1?tag=myother") ~> Accept(`application/ld+json`) ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual jsonContentOf("/errors/tag-not-found.json", "tag" -> "myother")
      }
    }

    "reject if provided rev and tag simultaneously" in {
      Get("/v1/files/org/proj/file1?tag=mytag&rev=1") ~> Accept(`application/ld+json`) ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("/errors/tag-and-rev-error.json")
      }
    }

    "delete a tag on file" in {
      Delete("/v1/files/org/proj/file1/tags/mytag?rev=4") ~> asWriter ~> routes ~> check {
        val attr = attributes("file-idx-1.txt")
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual fileMetadata(projectRef, file1, attr, diskIdRev, rev = 5, createdBy = s3writer)
      }
    }

    "not return the deleted tag" in {
      Get("/v1/files/org/proj/file1/tags") ~> asReader ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual json"""{"tags": []}""".addContext(contexts.tags)
      }
    }

    "fail to fetch file by the deleted tag" in {
      Get("/v1/files/org/proj/file1?tag=mytag") ~> Accept(`application/ld+json`) ~> asReader ~> routes ~> check {
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

  def givenAFile(test: String => Assertion): Assertion = {
    val id = genString()
    Put(s"/v1/files/org/proj/$id", entity(s"$id")) ~> asWriter ~> routes ~> check {
      status shouldEqual StatusCodes.Created
    }
    test(id)
  }

  def givenADeprecatedFile(test: String => Assertion): Assertion =
    givenAFile { id =>
      Delete(s"/v1/files/org/proj/$id?rev=1") ~> asWriter ~> routes ~> check {
        status shouldEqual StatusCodes.OK
      }
      test(id)
    }

  def fileMetadata(
      project: ProjectRef,
      id: Iri,
      attributes: FileAttributes,
      storage: ResourceRef.Revision,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdBy: Subject = callerWriter.subject,
      updatedBy: Subject = callerWriter.subject
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
