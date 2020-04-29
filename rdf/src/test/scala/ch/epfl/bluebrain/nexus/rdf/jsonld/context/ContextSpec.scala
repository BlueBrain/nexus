package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.ContextSpec._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{JsonLdFixtures, JsonLdOptions}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

class ContextSpec extends JsonLdFixtures {
  "A Context" should {
    "be decoded" in {
      forAll(expandTestCases) {
        case (inName, in, _, Some(_), options) if !excluded.contains(inName) =>
          implicit val opt: JsonLdOptions = options
          in.as[ContextWrapper].rightValue
        case _ => // ignore
      }
    }

    "merge value context with null context" in {
      val ctx1 = Context(Map("t1" -> Some(uri"http://a/t1")))
      ctx1.merge(Null) shouldEqual Null
    }

    "merge value context with other value context" in {
      val en   = LanguageTag("en").rightValue
      val ctx1 = Context(Map("t1" -> Some(uri"http://a/t1"), "t3" -> Some(uri"http://a/t3")), language = Val(en))
      val ctx2 = Context(Map("t1" -> Some(uri"http://b/t1"), "t2" -> Some(uri"http://b/t2")), language = Null)
      val expected =
        Context(Map("t1" -> Some(uri"http://b/t1"), "t2" -> Some(uri"http://b/t2"), "t3" -> Some(uri"http://a/t3")), language = Null)
      ctx1.merge(Val(ctx2)) shouldEqual Val(expected)
    }
  }
}

object ContextSpec {
  private[jsonld] val excluded = Set(
    "0013-in.jsonld", // already expanded
    "0038-in.jsonld", // @id as a blank node not supported
    "0075-in.jsonld", // @vocab as a blank node not supported
    "0117-in.jsonld", // term starting with : ":term"
    "0126-in.jsonld", // relative link resolution, not supported yet
    "0127-in.jsonld", // relative link resolution, not supported yet
    "0128-in.jsonld", // relative link resolution, not supported yet
    "c031-in.jsonld", // relative link resolution, not supported yet
    "c034-in.jsonld", // relative link resolution, not supported yet
    "so05-in.jsonld", // relative link resolution, not supported yet
    "so06-in.jsonld", // relative link resolution, not supported yet
    "so08-in.jsonld", // relative link resolution, not supported yet
    "pr30-in.jsonld", // @protected @context not supported
    "so09-in.jsonld", // @import not supported
    "so11-in.jsonld",  // @import not supported
    "so13-in.jsonld"  // @import not supported
  )
}
