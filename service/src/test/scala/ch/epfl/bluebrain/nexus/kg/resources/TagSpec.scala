package ch.epfl.bluebrain.nexus.kg.resources

import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Randomness}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import io.circe.Json
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TagSpec extends AnyWordSpecLike with Matchers with TestHelper with Randomness with EitherValues {

  abstract private class Ctx {
    val id                                      = Id(ProjectRef(genUUID), genIri)
    def jsonTag(rev: Long, value: String): Json =
      Json.obj("@id" -> id.value.asString.asJson, "tag" -> value.asJson, "rev" -> rev.asJson)

  }

  "A Tag" should {

    "be converted to tag case class correctly" in new Ctx {
      val rev = genInt().toLong
      val tag = genString()
      Tag(id, jsonTag(rev, tag)).rightValue shouldEqual Tag(rev, tag)
    }

    "reject when rev is missing" in new Ctx {
      val json = Json.obj("@id" -> id.value.asString.asJson, "tag" -> genString().asJson)
      Tag(id, json).leftValue shouldEqual InvalidResourceFormat(id.ref, "'rev' field does not have the right format.")
    }

    "reject when rev is not a number" in new Ctx {
      val json = Json.obj("@id" -> id.value.asString.asJson, "tag" -> genString().asJson, "rev" -> genString().asJson)
      Tag(id, json).leftValue shouldEqual InvalidResourceFormat(id.ref, "'rev' field does not have the right format.")
    }

    "reject when tag is missing" in new Ctx {
      val json = Json.obj("@id" -> id.value.asString.asJson, "rev" -> genInt().toLong.asJson)
      Tag(id, json).leftValue shouldEqual InvalidResourceFormat(id.ref, "'tag' field does not have the right format.")
    }

    "reject when tag is empty" in new Ctx {
      val json = Json.obj("@id" -> id.value.asString.asJson, "rev" -> genInt().toLong.asJson, "tag" -> "".asJson)
      Tag(id, json).leftValue shouldEqual InvalidResourceFormat(id.ref, "'tag' field does not have the right format.")
    }

    "reject when tag is not a string" in new Ctx {
      val json = Json.obj("@id" -> id.value.asString.asJson, "rev" -> genInt().toLong.asJson, "tag" -> genInt().asJson)
      Tag(id, json).leftValue shouldEqual InvalidResourceFormat(id.ref, "'tag' field does not have the right format.")
    }
  }

}
