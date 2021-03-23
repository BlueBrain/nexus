package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.MediaTypes.`application/x-tar`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Location, OAuth2BearerToken}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchiveDownload.ArchiveDownloadImpl
import ch.epfl.bluebrain.nexus.delta.plugins.archive.routes.ArchiveRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileFixtures, FileReferenceExchange, Files, FilesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, Permissions}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import com.typesafe.config.Config
import io.circe.Json
import io.circe.parser.parse
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, Inspectors, TryValues}
import slick.jdbc.JdbcBackend

import java.nio.file.{Files => JFiles}
import java.util.UUID

class ArchiveRoutesSpec
    extends AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with ScalatestRouteTest
    with TryValues
    with Inspectors
    with ConfigFixtures
    with StorageFixtures
    with FileFixtures
    with RemoteContextResolutionFixture
    with IOFixedClock
    with RouteHelpers {

  override def testConfig: Config = AbstractDBSpec.config

  import akka.actor.typed.scaladsl.adapter._
  implicit private val typedSystem: ActorSystem[Nothing] = system.toTyped
  implicit private val scheduler: Scheduler              = Scheduler.global

  private var db: JdbcBackend.Database = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    db = AbstractDBSpec.beforeAll
    ()
  }

  override protected def afterAll(): Unit = {
    AbstractDBSpec.afterAll(db)
    super.afterAll()
  }

  implicit private val baseUri: BaseUri   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val subject: Subject   = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller     = Caller.unsafe(subject)
  private val subjectNoFilePerms: Subject = Identity.User("nofileperms", Label.unsafe("realm"))
  private val callerNoFilePerms: Caller   = Caller.unsafe(subjectNoFilePerms)

  implicit private val httpClient: HttpClient           = HttpClient()(httpClientConfig, system, scheduler)
  implicit private val jsonKeyOrdering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys = List("@context", "@id", "@type", "reason", "details", "_total", "_results"))

  implicit private val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit private val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  private val cfg            = config.copy(
    disk = config.disk.copy(defaultMaxFileSize = 500, allowedVolumes = config.disk.allowedVolumes + path)
  )
  private val storagesConfig = StoragesConfig(aggregate, keyValueStore, pagination, indexing, cfg)
  private val filesConfig    = FilesConfig(aggregate, indexing)
  private val archivesConfig = ArchivePluginConfig.load(ArchivesSpec.config).accepted

  private val allowedPerms = Set(
    diskFields.readPermission.value,
    diskFields.writePermission.value,
    Permissions.resources.write,
    Permissions.resources.read,
    model.permissions.read,
    model.permissions.write
  )

  private val aclSetup = AclSetup.init(
    (subject, AclAddress.Root, allowedPerms),
    (
      subjectNoFilePerms,
      AclAddress.Root,
      allowedPerms - diskFields.readPermission.value - diskFields.writePermission.value
    )
  )

  private val asSubject     = addCredentials(OAuth2BearerToken("subject"))
  private val asNoFilePerms = addCredentials(OAuth2BearerToken("nofileperms"))
  private val acceptMeta    = Accept(`application/ld+json`)
  private val acceptAll     = Accept(`*/*`)

  private lazy val (routes, files) = (for {
    eventLog         <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
    acls             <- aclSetup
    (orgs, projects) <- ProjectSetup.init(
                          orgsToCreate = org :: Nil,
                          projectsToCreate = project :: deprecatedProject :: Nil,
                          projectsToDeprecate = deprecatedProject.ref :: Nil
                        )
    identities        = IdentitiesDummy(Map(AuthToken("subject") -> caller, AuthToken("nofileperms") -> callerNoFilePerms))
    perms            <- PermissionsDummy(allowedPerms)
    resolverCtx       = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
    storages         <- Storages(storagesConfig, eventLog, resolverCtx, perms, orgs, projects, (_, _) => IO.unit)
    eventLog         <- EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors
    files            <- Files(filesConfig, eventLog, acls, orgs, projects, storages)
    storageJson       = diskFieldsJson.map(_ deepMerge json"""{"maxFileSize": 300, "volume": "$path"}""")
    _                <- storages.create(diskId, projectRef, storageJson)
    archiveDownload   = new ArchiveDownloadImpl(List(new FileReferenceExchange(files)), acls, files)
    archives         <- Archives(projects, archiveDownload, archivesConfig)
    routes            = new ArchiveRoutes(archives, identities, acls, projects)
  } yield (Route.seal(routes.routes), files)).accepted

  private def archiveMetadata(
      id: Iri,
      ref: ProjectRef,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = subject,
      updatedBy: Subject = subject,
      expiresInSeconds: Long = 18000L,
      label: Option[String] = None
  ): Json =
    jsonContentOf(
      "responses/archive-metadata-response.json",
      "project"          -> ref,
      "id"               -> id,
      "rev"              -> rev,
      "deprecated"       -> deprecated,
      "createdBy"        -> createdBy.id,
      "updatedBy"        -> updatedBy.id,
      "label"            -> label.fold(lastSegment(id))(identity),
      "expiresInSeconds" -> expiresInSeconds.toString
    )

  private def lastSegment(iri: Iri) =
    iri.toString.substring(iri.toString.lastIndexOf("/") + 1)

  private def archiveMapOf(source: AkkaSource): Map[String, String] = {
    val path   = JFiles.createTempFile("test", ".tar")
    source.runWith(FileIO.toPath(path)).futureValue()
    val result = FileIO
      .fromPath(path)
      .via(Archive.tarReader())
      .mapAsync(1) { case (metadata, source) =>
        source
          .runFold(ByteString.empty) { case (bytes, elem) =>
            bytes ++ elem
          }
          .map { bytes =>
            (metadata.filePath, bytes.utf8String)
          }
      }
      .runFold(Map.empty[String, String]) { case (map, elem) =>
        map + elem
      }
      .futureValue()
    result
  }

  "The ArchiveRoutes" should {
    val fileId        = iri"http://localhost/${genString()}"
    val encodedFileId = UrlUtils.encode(fileId.toString)
    val notFoundId    = iri"http://localhost/${genString()}"

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

    val archiveCtxJson = jsonContentOf("responses/archive-resource-context.json")

    "create required file" in {
      files.create(fileId, Some(diskId), project.ref, entity()).accepted
    }

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
      val encodedId = UrlUtils.encode(id.toString).replaceAll("%3A", ":")
      Put(s"/v1/archives/$projectRef/$encodedId", archive.toEntity) ~> asSubject ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        response.asJson shouldEqual archiveMetadata(id, project.ref, label = Some(encodedId))
      }
    }

    "create an archive with a specific id and redirect" in {
      val id        = iri"http://localhost/${genString()}"
      val encodedId = UrlUtils.encode(id.toString).replaceAll("%3A", ":")
      Put(s"/v1/archives/$projectRef/$encodedId", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.SeeOther
        header[Location].value.uri.toString() shouldEqual s"${baseUri.endpoint}/archives/$projectRef/$encodedId"
      }
    }

    "fetch an archive json representation" in {
      Get(s"/v1/archives/$projectRef/$uuid") ~> asSubject ~> acceptMeta ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual archiveMetadata(generatedId, project.ref)
          .deepMerge(archive)
          .deepMerge(archiveCtxJson)
      }
    }

    "fetch an archive ignoring not found" in {
      forAll(List(Accept(`application/x-tar`), acceptAll)) { accept =>
        Get(s"/v1/archives/$projectRef/$uuid?ignoreNotFound=true") ~> asSubject ~> accept ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          header[`Content-Type`].value.value() shouldEqual `application/x-tar`.value
          val result    = archiveMapOf(responseEntity.dataBytes)
          val attr      = attributes()
          val diskIdRev = ResourceRef.Revision(diskId, 1)
          val metadata  =
            RouteFixtures.fileMetadata(
              projectRef,
              fileId,
              attr,
              diskIdRev,
              createdBy = subject,
              updatedBy = subject,
              label = Some(encodedFileId.replaceAll("%3A", ":"))
            )
          result.keySet shouldEqual Set(
            s"${project.ref}/file.txt",
            s"${project.ref}/${UrlUtils.encode(fileId.toString)}.json"
          )

          val expectedContent = content
          val actualContent   = result.get(s"${project.ref}/file.txt").value
          actualContent shouldEqual expectedContent

          val expectedMetadata = metadata
          val actualMetadata   =
            parse(result.get(s"${project.ref}/${UrlUtils.encode(fileId.toString)}.json").value).rightValue
          actualMetadata shouldEqual expectedMetadata
        }
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

    "fail to create an archive in a deprecated project" in {
      Post(s"/v1/archives/${deprecatedProject.ref}", archive.toEntity) ~> asSubject ~> acceptAll ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
        response.asJson shouldEqual jsonContentOf("responses/deprecated-project.json")
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
