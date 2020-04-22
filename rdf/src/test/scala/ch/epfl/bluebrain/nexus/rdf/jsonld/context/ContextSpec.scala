package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.ContextSpec._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, JsonLdFixtures}
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

class ContextSpec extends JsonLdFixtures {
  "A Context" should {
    "be decoded" in {
      forAll(expandTestCases) {
        case ((inName, in), (_, Some(_))) if !excluded.contains(inName) =>
          in.hcursor.get[Option[Context]](keyword.context).rightValue
        case _ => // ignore
      }
    }

    "merge value context with null context" in {
      val ctx1 = Context(Map("t1" -> uri"http://a/t1"))
      ctx1.merge(Null) shouldEqual Null
    }

    "merge value context with other value context" in {
      val en   = LanguageTag("en").rightValue
      val ctx1 = Context(Map("t1" -> uri"http://a/t1", "t3" -> uri"http://a/t3"), language = Val(en))
      val ctx2 = Context(Map("t1" -> uri"http://b/t1", "t2" -> uri"http://b/t2"), language = Null)
      val expected =
        Context(Map("t1" -> uri"http://b/t1", "t2" -> uri"http://b/t2", "t3" -> uri"http://a/t3"), language = Null)
      ctx1.merge(Val(ctx2)) shouldEqual Val(expected)
    }

    "failed to be decoded" in {
      forAll(expandTestCases) {
        case ((inName, in), (_, None)) if !excludedFailed.contains(inName) =>
          in.hcursor.get[Option[Context]](keyword.context).leftValue
        case _ => //ignore
      }
    }
  }
}

object ContextSpec {
  private[jsonld] val excluded = Set(
    "0013-in.jsonld", // already expanded
    "0032-in.jsonld", // null keys not supported yet
    "0038-in.jsonld", // @id as a blank node not supported
    "0075-in.jsonld", // @vocab as a blank node not supported
    "0089-in.jsonld", // @base relative not supported
    "0090-in.jsonld", // @base relative not supported
    "0091-in.jsonld", // @base relative not supported
    "0092-in.jsonld", // @vocab with empty value "" not supported
    "0115-in.jsonld", // @vocab with empty value "" not supported
    "0117-in.jsonld", // term starting with : ":term"
    "0126-in.jsonld", // relative link resolution, not supported
    "0127-in.jsonld", // relative link resolution, not supported
    "0128-in.jsonld", // relative link resolution, not supported
    "c031-in.jsonld", // relative link resolution, not supported
    "c034-in.jsonld", // relative link resolution, not supported
    "in06-in.jsonld", // null term value, not supported
    "pr15-in.jsonld", // context with a link with null value
    "pr16-in.jsonld", // context with a link with null value
    "pr19-in.jsonld", // context with a link with null value
    "so05-in.jsonld", // relative link resolution, not supported
    "so06-in.jsonld", // relative link resolution, not supported
    "so08-in.jsonld", // relative link resolution, not supported
    "so09-in.jsonld", // @import not supported
    "so11-in.jsonld"  // @import not supported
  )

  private[jsonld] val excludedFailed = Set(
    "0116-in.jsonld",
    "0123-in.jsonld",
    "c029-in.jsonld",
    "di09-in.jsonld",
    "en01-in.jsonld",
    "en02-in.jsonld",
    "en03-in.jsonld",
    "en04-in.jsonld",
    "ep02-in.jsonld",
    "er10-in.jsonld", // failed to detect cyclic IRI mapping
    "er21-in.jsonld", // failed to discover invalid container mapping
    "er24-in.jsonld", // failure is on the object nodes, not on the context
    "er25-in.jsonld", // failure is on the object nodes, not on the context
    "er26-in.jsonld", // failure is on the object nodes, not on the context
    "er27-in.jsonld", // failure is on the object nodes, not on the context
    "er28-in.jsonld", // failure is on the object nodes, not on the context
    "er29-in.jsonld", // failure is on the object nodes, not on the context
    "er30-in.jsonld", // failure is on the object nodes, not on the context
    "er31-in.jsonld", // failure is on the object nodes, not on the context
    "er32-in.jsonld", // failure is on the object nodes, not on the context
    "er33-in.jsonld", // failure is on the object nodes, not on the context
    "er34-in.jsonld", // failure is on the object nodes, not on the context
    "er35-in.jsonld", // failure is on the object nodes, not on the context
    "er36-in.jsonld", // failure is on the object nodes, not on the context
    "er37-in.jsonld", // failure is on the object nodes, not on the context
    "er37-in.jsonld", // failure is on the object nodes, not on the context
    "er38-in.jsonld", // failure is on the object nodes, not on the context
    "er39-in.jsonld", // failure is on the object nodes, not on the context
    "er40-in.jsonld", // failure is on the object nodes, not on the context
    "er41-in.jsonld", // failure is on the object nodes, not on the context
    "er42-in.jsonld", // failure is on the object nodes, not on the context
    "er43-in.jsonld", // failure is on the object nodes, not on the context
    "er51-in.jsonld", // failure is on the object nodes, not on the context
    "es01-in.jsonld", // only applies for json-ld 1.0 algorithm
    "in07-in.jsonld", // failure is on the object nodes, not on the context
    "in08-in.jsonld", // failure is on the object nodes, not on the context
    "in09-in.jsonld", // failure is on the object nodes, not on the context
    "pi01-in.jsonld", // only applies for json-ld 1.0 algorithm
    "pi05-in.jsonld", // failure is on the object nodes, not on the context
    "pr01-in.jsonld", // scoped context on node object not supported yet
    "pr03-in.jsonld", // scoped context on node object not supported yet
    "pr04-in.jsonld", // scoped context on node object not supported yet
    "pr05-in.jsonld", // scoped context on node object not supported yet
    "pr08-in.jsonld", // scoped context on node object not supported yet
    "pr09-in.jsonld", // scoped context on node object not supported yet
    "pr11-in.jsonld", // scoped context on node object not supported yet
    "pr12-in.jsonld", // scoped context on node object not supported yet
    "pr17-in.jsonld", // protected keyword not supported yet
    "pr18-in.jsonld", // protected keyword not supported yet
    "pr20-in.jsonld", // protected keyword not supported yet
    "pr21-in.jsonld", // protected keyword not supported yet
    "pr26-in.jsonld", // protected keyword not supported yet
    "pr28-in.jsonld", // protected keyword not supported yet
    "pr31-in.jsonld", // protected keyword not supported yet
    "pr32-in.jsonld", // protected keyword not supported yet
    "tn01-in.jsonld"  // only applies for json-ld 1.0 algorithm
  )
}
