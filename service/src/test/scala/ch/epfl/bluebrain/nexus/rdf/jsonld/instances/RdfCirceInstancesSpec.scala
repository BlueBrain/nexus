package ch.epfl.bluebrain.nexus.rdf.jsonld.instances

import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path, RelativeIri, Url, Urn}
import ch.epfl.bluebrain.nexus.rdf._
import io.circe.Json
import io.circe.literal._
import io.circe.syntax._

class RdfCirceInstancesSpec extends RdfSpec {
  private val iriString = "http://example.com/a/b?c=d"
  private val iri: Iri  = Iri.absolute(iriString).rightValue

  "An Iri" should {
    "be encoded" in {
      iri.asJson shouldEqual Json.fromString(iriString)
    }
    "be decoded" in {
      Json.fromString(iriString).as[Iri].rightValue shouldEqual iri
    }
    "fail to decode" in {
      Json.fromString("").as[Iri].left
    }
  }

  "An AbsoluteIri" should {
    "be encoded" in {
      iri.asAbsolute.value.asJson shouldEqual Json.fromString(iriString)
    }
    "be decoded" in {
      Json.fromString(iriString).as[AbsoluteIri].rightValue shouldEqual iri.asAbsolute.value
    }
    "fail to decode" in {
      Json.fromString("/a/b/c").as[AbsoluteIri].left
    }
  }

  "A Path" should {
    val pathString = "/a/b"
    val path       = iri.asAbsolute.value.path
    "be encoded" in {
      path.asJson shouldEqual Json.fromString(pathString)
    }
    "be decoded" in {
      Json.fromString(pathString).as[Path].rightValue shouldEqual path
    }
    "fail to decode" in {
      Json.fromString("https://example.com").as[Path].left
    }
  }

  "A Url" should {
    val urlString = "http://example.com/a"
    val url       = Iri.url("http://example.com/a").rightValue
    "be encoded" in {
      url.asJson shouldEqual Json.fromString(urlString)
    }
    "be decoded" in {
      Json.fromString(urlString).as[Url].rightValue shouldEqual url
    }
    "fail to decode" in {
      Json.fromString("urn:example:a£/bÆc//:://?=a=b#").as[Url].left
    }
  }

  "A Urn" should {
    val urnString = "urn:example:a£/bÆc//:://?=a=b#"
    val urn       = Iri.urn("urn:example:a£/bÆc//:://?=a=b#").rightValue
    "be encoded" in {
      urn.asJson shouldEqual Json.fromString(urnString)
    }
    "be decoded" in {
      Json.fromString(urnString).as[Urn].rightValue shouldEqual urn
    }
    "fail to decode" in {
      Json.fromString("https://example.com").as[Urn].left
    }
  }

  "A RelativeIri" should {
    val relativeIriString = "../../../"
    val relativeIri       = Iri.relative("../../../").rightValue
    "be encoded" in {
      relativeIri.asJson shouldEqual Json.fromString(relativeIriString)
    }
    "be decoded" in {
      Json.fromString(relativeIriString).as[RelativeIri].rightValue shouldEqual relativeIri
    }
    "fail to decode" in {
      Json.fromString("https://example.com").as[RelativeIri].left
    }
  }

  "A Json" should {
    "be encoded" in {
      val json = json"""{"value": 3}"""
      GraphEncoder[Json].apply(json) shouldEqual Graph("""{"value":3}""")
    }
    "be decoded" in {
      val graph = Graph("""{"value":3}""")
      GraphDecoder[Json].apply(graph.cursor).rightValue shouldEqual Json.obj("value" -> Json.fromInt(3))
    }
    "fail to decode" in {
      val graph = Graph("""{"value":3, incorrect}""")
      GraphDecoder[Json].apply(graph.cursor).leftValue
    }
  }
}
