package ch.epfl.bluebrain.nexus.kg.serializers

import java.nio.charset.Charset
import java.nio.file.Paths
import java.time.Clock
import java.util.UUID
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.service.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.resources.Event._
import ch.epfl.bluebrain.nexus.kg.resources.StorageReference.{RemoteDiskStorageReference, S3StorageReference}
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.{Id, OrganizationRef, Ref, ResId}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.serializers.Serializer.EventSerializer
import ch.epfl.bluebrain.nexus.kg.storage.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Permissions.{files, resources}
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageFileDigest}
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import io.circe.Json
import io.circe.parser._
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import shapeless.Typeable

import scala.concurrent.duration._

class EventSerializerSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with EitherValues
    with ScalatestRouteTest
    with OptionValues
    with Resources
    with TestHelper {

  implicit private val appConfig = Settings(system).appConfig

  final private val UTF8: Charset    = Charset.forName("UTF-8")
  final private val serialization    = SerializationExtension(system)
  implicit private val storageConfig =
    appConfig.storage.copy(
      DiskStorageConfig(Paths.get("/tmp/"), "SHA-256", resources.read, files.write, false, 1024L),
      RemoteDiskStorageConfig("http://example.com", "v1", None, "SHA-256", resources.read, files.write, true, 1024L),
      S3StorageConfig("MD5", resources.read, files.write, true, 1024L),
      "password",
      "salt",
      RetryStrategyConfig("linear", 300.millis, 5.minutes, 100, 1.second)
    )
  private case class Other(str: String)

  private def findConcreteSerializer[A <: SerializerWithStringManifest](o: AnyRef)(implicit t: Typeable[A]): A =
    t.cast(serialization.findSerializerFor(o)).getOrElse(fail("Expected a SerializerWithManifest"))

  "A Serializer" when {

    val key: ResId =
      Id(
        ProjectRef(UUID.fromString("4947db1e-33d8-462b-9754-3e8ae74fcd4e")),
        url"https://bbp.epfl.ch/nexus/data/resourceName"
      )

    val orgRef = OrganizationRef(UUID.fromString("17a62c6a-4dc4-4eaa-b418-42d0634695a1"))

    val schema: Ref = Ref(url"https://bbp.epfl.ch/nexus/data/schemaName")

    val types = Set[AbsoluteIri](url"https://bbp.epfl.ch/nexus/types/type1", url"https://bbp.epfl.ch/nexus/types/type2")

    val instant = Clock.systemUTC.instant()
    val subject = User("sub:1234", "realm")

    val rep = Map(quote("{timestamp}") -> instant.toString)

    "using EventSerializer" should {
      val value      = Json.obj("key" -> Json.obj("value" -> Json.fromString("seodhkxtudwlpnwb")))
      val storage    = DiskStorage.default(key.parent)
      val digest     = Digest("MD5", "1234")
      val filedUuid  = UUID.fromString("b1d7cda2-1ec0-40d2-b12e-3baf4895f7d7")
      val fileAttr   =
        FileAttributes(
          filedUuid,
          Uri(Paths.get("/test/path").toUri.toString),
          Uri.Path("path"),
          "test-file.json",
          `application/json`,
          128L,
          digest
        )
      val s3fileAttr =
        FileAttributes(
          filedUuid,
          Uri("s3://test/path"),
          Uri.Path("path"),
          "test-file.json",
          `application/json`,
          128L,
          digest
        )
      val results    = List(
        Created(key, orgRef, schema, types, value, instant, Anonymous)          -> jsonContentOf(
          "/serialization/created-resp.json",
          rep
        ),
        Deprecated(key, orgRef, 1L, types, instant, subject)                    -> jsonContentOf(
          "/serialization/deprecated-resp.json",
          rep
        ),
        TagAdded(key, orgRef, 1L, 2L, "tagName", instant, subject)              -> jsonContentOf(
          "/serialization/tagged-resp.json",
          rep
        ),
        FileCreated(key, orgRef, storage.reference, fileAttr, instant, subject) -> jsonContentOf(
          "/serialization/created-file-resp.json",
          rep
        ),
        FileUpdated(
          key,
          orgRef,
          S3StorageReference(url"https://bbp.epfl.ch/nexus/storages/org/proj/s3", 2L),
          2L,
          s3fileAttr,
          instant,
          subject
        )                                                                       -> jsonContentOf("/serialization/updated-file-resp.json", rep),
        FileAttributesUpdated(
          key,
          orgRef,
          RemoteDiskStorageReference(
            url"https://bbp.epfl.ch/nexus/storages/org/proj/remote",
            1L
          ),
          2L,
          StorageFileAttributes(
            "file:///some/remote/location/data/project/proj42/nexus/0d06da77-b13a-48f4-8a37-a411fd6f21c9/b/c/6/5/e/7/c/1/r160127_0101_04.json",
            257813L,
            StorageFileDigest("MD5", "1234"),
            `application/json`
          ),
          instant,
          subject
        )                                                                       -> jsonContentOf("/serialization/file-attributes-updated-event.json", rep)
      )

      "encode known events to UTF-8" in {
        forAll(results) {
          case (event, json) =>
            val serializer = findConcreteSerializer[EventSerializer](event)
            parse(new String(serializer.toBinary(event), UTF8)).rightValue shouldEqual json
            serializer.manifest(event)
        }
      }

      "decode known events" in {
        forAll(results) {
          case (event, json) =>
            val serializer = findConcreteSerializer[EventSerializer](event)
            serializer.fromBinary(json.noSpaces.getBytes(UTF8), "Event") shouldEqual event
        }
      }
    }
  }
}
