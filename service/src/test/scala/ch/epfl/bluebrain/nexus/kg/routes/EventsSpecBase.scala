package ch.epfl.bluebrain.nexus.kg.routes

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.organizations.Organization
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.{ResourceF => AdminResourceF}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists}
import ch.epfl.bluebrain.nexus.iam.types.Identity.User
import ch.epfl.bluebrain.nexus.iam.types.{Caller, ResourceF}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.Event._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Ref.Latest
import ch.epfl.bluebrain.nexus.kg.resources.StorageReference.S3StorageReference
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.{Id, OrganizationRef}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.{Permissions, Settings}
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import org.mockito.IdiomaticMockito
import org.mockito.matchers.MacroBasedMatchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}

import scala.concurrent.duration._

class EventsSpecBase
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with OptionValues
    with EitherValues
    with Inspectors
    with TestHelper
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 100.milliseconds)

  override def testConfig: Config = ConfigFactory.load("test.conf")

  val base = url"http://example.com/base"

  val instant = Instant.EPOCH
  val subject = User("uuid", "myrealm")

  val acls   = AccessControlLists(
    Path./ -> ResourceF[AccessControlList](
      base + UUID.randomUUID().toString,
      2L,
      Set(base + "AccessControlList"),
      instant,
      subject,
      instant,
      subject,
      AccessControlList(subject -> Set(Permissions.resources.read, Permissions.events.read))
    )
  )
  val caller = Caller(subject, Set(subject))

  val projectUuid = UUID.fromString("7f8039a0-3141-11e9-b210-d663bd873d93")
  val orgUuid     = UUID.fromString("17a62c6a-4dc4-4eaa-b418-42d0634695a1")
  val fileUuid    = UUID.fromString("a733d99e-6075-45df-b6c3-52c8071df4fb")
  val schemaRef   = Latest(base + "schema")
  val types       = Set(base + "type")

  implicit val appConfig  = Settings(system).appConfig
  implicit val storageCfg = appConfig.storage
  val orgRef              = OrganizationRef(orgUuid)
  // format: off
  implicit val project = AdminResourceF(base + "org" + "project", genUUID, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Project("project", orgUuid, "org", None, Map.empty, base, base + "vocab"))
  val organization = AdminResourceF(base + "org", orgUuid, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Organization("org", None))
  // format: on

  val projectRef = ProjectRef(projectUuid)

  val events = List(
    Created(
      Id(projectRef, base + "Created"),
      orgRef,
      schemaRef,
      types,
      Json.obj(
        "@type" -> Json.fromString("created")
      ),
      instant,
      subject
    ),
    Updated(
      Id(projectRef, base + "created"),
      orgRef,
      2L,
      types,
      Json.obj(
        "@type" -> Json.fromString("Updated")
      ),
      instant,
      subject
    ),
    Deprecated(
      Id(projectRef, base + "created"),
      orgRef,
      3L,
      types,
      instant,
      subject
    ),
    TagAdded(
      Id(projectRef, base + "created"),
      orgRef,
      4L,
      2L,
      "v1.0.0",
      instant,
      subject
    ),
    FileCreated(
      Id(projectRef, base + "file"),
      orgRef,
      DiskStorage.default(projectRef).reference,
      FileAttributes(
        fileUuid,
        Uri("/some/location/path"),
        Uri.Path("path"),
        "attachment.json",
        `application/json`,
        47,
        Digest("SHA-256", "00ff4b34e3f3695c3abcdec61cba72c2238ed172ef34ae1196bfad6a4ec23dda")
      ),
      instant,
      subject
    ),
    FileUpdated(
      Id(projectRef, base + "file"),
      orgRef,
      S3StorageReference(base + "storages" + "s3", 1L),
      2L,
      FileAttributes(
        fileUuid,
        Uri("/some/location/path"),
        Uri.Path("path2"),
        "attachment.json",
        `text/plain(UTF-8)`,
        47,
        Digest("SHA-256", "00ff4b34e3f3695c3abcdec61cba72c2238ed172ef34ae1196bfad6a4ec23dda")
      ),
      instant,
      subject
    )
  )

  def eventStreamFor(jsons: Vector[Json], drop: Int = 0): String =
    jsons.zipWithIndex
      .drop(drop)
      .map {
        case (json, idx) =>
          val data  = json.noSpaces
          val event = json.hcursor.get[String]("@type").rightValue
          val id    = idx
          s"""data:$data
             |event:$event
             |id:$id""".stripMargin
      }
      .mkString("", "\n\n", "\n\n")

}
