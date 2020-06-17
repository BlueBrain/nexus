package ch.epfl.bluebrain.nexus.kg.resources

import akka.http.scaladsl.model.ContentTypes._
import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Randomness}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import ch.epfl.bluebrain.nexus.kg.resources.file.File.FileAttributes
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import io.circe.Json
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class FileAttributesSpec extends AnyWordSpecLike with Matchers with TestHelper with Randomness with EitherValues {

  abstract private class Ctx {

    val id = Id(ProjectRef(genUUID), genIri)

    def jsonFileAttr(
        digest: StorageDigest,
        mediaType: String,
        location: String,
        bytes: Long,
        tpe: String = nxv.UpdateFileAttributes.prefix
    ): Json =
      Json.obj(
        "@id"       -> id.value.asString.asJson,
        "@type"     -> tpe.asJson,
        "digest"    -> Json.obj("value" -> digest.value.asJson, "algorithm" -> digest.algorithm.asJson),
        "mediaType" -> mediaType.asJson,
        "location"  -> location.asJson,
        "bytes"     -> bytes.asJson
      )

    val digest = StorageDigest("SHA-256", genString())

  }

  "A storage FileAttributes" should {

    "be converted to file attributes case class correctly" in new Ctx {
      val expected = StorageFileAttributes("http://example.com", 10L, digest, `application/json`)
      val json     = jsonFileAttr(digest, "application/json", "http://example.com", 10L)
      FileAttributes(id, json).rightValue shouldEqual expected
    }

    "reject when algorithm digest is missing" in new Ctx {
      val json = jsonFileAttr(digest, "application/json", "http://example.com", 10L).removeKeys("digest")
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'algorithm' field does not have the right format.")
    }

    "reject when digest value is empty" in new Ctx {
      override val digest = StorageDigest("SHA-256", "")
      val json            = jsonFileAttr(digest, "application/json", "http://example.com", 10L)
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'value' field does not have the right format.")
    }

    "reject when algorithm is empty" in new Ctx {
      override val digest = StorageDigest("", genString())
      val json            = jsonFileAttr(digest, "application/json", "http://example.com", 10L)
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'algorithm' field does not have the right format.")
    }

    "reject when algorithm is invalid" in new Ctx {
      override val digest = StorageDigest(genString(), genString())
      val json            = jsonFileAttr(digest, "application/json", "http://example.com", 10L)
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'algorithm' field does not have the right format.")
    }

    "reject when @type is missing" in new Ctx {
      val json = jsonFileAttr(digest, "application/json", "http://example.com", 10L).removeKeys("@type")
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'@type' field does not have the right format.")
    }

    "reject when @type is invalid" in new Ctx {
      val json = jsonFileAttr(digest, "application/json", "http://example.com", 10L, genIri.asString)
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'@type' field does not have the right format.")
    }

    "reject when mediaType is invalid" in new Ctx {
      val json = jsonFileAttr(digest, "wrong", "http://example.com", 10L)
      FileAttributes(id, json).leftValue shouldEqual
        InvalidResourceFormat(id.ref, "'mediaType' field does not have the right format.")
    }
  }

}
