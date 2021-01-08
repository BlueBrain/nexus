package ch.epfl.bluebrain.nexus.migration.v1_4.serializer

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.Event._
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.StorageReference.{DiskStorageReference, RemoteDiskStorageReference, S3StorageReference}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.{Digest, FileAttributes, StorageFileAttributes}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors, OptionValues}

import java.nio.charset.Charset
import java.nio.file.Paths
import java.time.Clock
import java.util.UUID

class KgEventSerializerSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with EitherValues
    with OptionValues
    with TestHelpers {

  final private val UTF8: Charset = Charset.forName("UTF-8")

  private val serializer = new KgEventSerializer.EventSerializer()

  "A Serializer" when {

    val id          = iri"https://bbp.epfl.ch/nexus/data/resourceName"
    val projectUuid = UUID.fromString("4947db1e-33d8-462b-9754-3e8ae74fcd4e")

    val orgUuid             = UUID.fromString("17a62c6a-4dc4-4eaa-b418-42d0634695a1")
    val schema: ResourceRef = Latest(iri"https://bbp.epfl.ch/nexus/data/schemaName")

    val types = Set[Iri](iri"https://bbp.epfl.ch/nexus/types/type1", iri"https://bbp.epfl.ch/nexus/types/type2")

    val instant = Clock.systemUTC.instant()
    val subject = User("sub:1234", Label.unsafe("realm"))

    val rep = "timestamp" -> instant.toString

    "using EventSerializer" should {
      val value            = Json.obj("key" -> Json.obj("value" -> Json.fromString("seodhkxtudwlpnwb")))
      val storageReference = DiskStorageReference(nxv + "diskStorageDefault", 1L)
      val digest           = Digest("MD5", "1234")
      val filedUuid        = UUID.fromString("b1d7cda2-1ec0-40d2-b12e-3baf4895f7d7")
      val fileAttr         =
        FileAttributes(
          filedUuid,
          Uri(Paths.get("/test/path").toUri.toString),
          Uri.Path("path"),
          "test-file.json",
          `application/json`,
          128L,
          digest
        )
      val s3fileAttr       =
        FileAttributes(
          filedUuid,
          Uri("s3://test/path"),
          Uri.Path("path"),
          "test-file.json",
          `application/json`,
          128L,
          digest
        )
      val results          = List(
        Created(id, projectUuid, orgUuid, schema, types, value, instant, Anonymous)              -> jsonContentOf(
          "/serialization/created-resp.json",
          rep
        ),
        Deprecated(id, projectUuid, orgUuid, 1L, types, instant, subject)                        -> jsonContentOf(
          "/serialization/deprecated-resp.json",
          rep
        ),
        TagAdded(id, projectUuid, orgUuid, 1L, 2L, TagLabel.unsafe("tagName"), instant, subject) -> jsonContentOf(
          "/serialization/tagged-resp.json",
          rep
        ),
        FileCreated(id, projectUuid, orgUuid, storageReference, fileAttr, instant, subject)      -> jsonContentOf(
          "/serialization/created-file-resp.json",
          rep
        ),
        FileUpdated(
          id,
          projectUuid,
          orgUuid,
          S3StorageReference(iri"https://bbp.epfl.ch/nexus/storages/org/proj/s3", 2L),
          2L,
          s3fileAttr,
          instant,
          subject
        )                                                                                        -> jsonContentOf("/serialization/updated-file-resp.json", rep),
        FileAttributesUpdated(
          id,
          projectUuid,
          orgUuid,
          RemoteDiskStorageReference(
            iri"https://bbp.epfl.ch/nexus/storages/org/proj/remote",
            1L
          ),
          2L,
          StorageFileAttributes(
            "file:///some/remote/location/data/project/proj42/nexus/0d06da77-b13a-48f4-8a37-a411fd6f21c9/b/c/6/5/e/7/c/1/r160127_0101_04.json",
            257813L,
            Digest("MD5", "1234"),
            `application/json`
          ),
          instant,
          subject
        )                                                                                        -> jsonContentOf("/serialization/file-attributes-updated-event.json", rep)
      )

      "decode known events" in {
        forAll(results) { case (event, json) =>
          serializer.fromBinary(json.noSpaces.getBytes(UTF8), "Event") shouldEqual event
        }
      }
    }
  }
}
