package ch.epfl.bluebrain.nexus.rdf.jena

import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Node.{IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.jena.Fixture._
import ch.epfl.bluebrain.nexus.rdf.jena.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.{Node, RdfSpec}
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.rdf.model._
import org.apache.jena.rdf.model.impl.ResourceImpl

class JenaConvertersSpec extends RdfSpec {

  "A Literal" should {
    "be converted correctly" in {
      val cases = List(
        ResourceFactory.createLangLiteral("a", "en-US")                 -> Literal("a", LanguageTag("en-US").rightValue),
        ResourceFactory.createStringLiteral("a")                        -> Literal("a"),
        ResourceFactory.createTypedLiteral("1", XSDDatatype.XSDinteger) -> Literal(1),
        ResourceFactory.createTypedLiteral("1.0", XSDDatatype.XSDfloat) -> Literal(1f),
        ResourceFactory.createLangLiteral("1", "en")                    -> Literal("1", LanguageTag("en").rightValue)
      )
      forAll(cases) {
        case (jl, l) =>
          jl.asRdfLiteral.rightValue shouldEqual l
      }
    }
    "fail to convert" in {
      val cases = List(
        ResourceFactory.createLangLiteral("a", "^%$^%4adS")
      )
      forAll(cases) { jl => jl.asRdfLiteral.leftValue }
    }
  }

  "A Resource" should {
    "be converted correctly" when {
      "target is an IriNode" in {
        ResourceFactory.createResource(xsd.integer.asUri).asRdfIriNode.rightValue.value shouldEqual xsd.integer
      }
      "target is a BNode" in {
        ResourceFactory.createResource().asRdfBNode.rightValue.id
      }
      "target is an IriOrBNode" in {
        val cases = List(
          ResourceFactory.createResource(),
          ResourceFactory.createResource(xsd.integer.asUri)
        )
        forAll(cases) { res => res.asRdfIriOrBNode.rightValue }
      }
    }
    "fail to convert" in {
      val cases = List(
        ResourceFactory.createResource().asRdfIriNode,
        ResourceFactory.createResource(xsd.integer.asUri).asRdfBNode
      )
      forAll(cases) { result => result.leftValue }
    }
  }

  "An RDFNode" should {
    "be converted correctly" in {
      val cases = List[(RDFNode, Node)](
        ResourceFactory.createLangLiteral("1", "en")      -> Literal("1", LanguageTag("en").rightValue),
        ResourceFactory.createResource(xsd.integer.asUri) -> xsd.integer,
        new ResourceImpl(AnonId.create("blah"))           -> b"blah"
      )
      forAll(cases) {
        case (rn, n) =>
          rn.asRdfNode.rightValue shouldEqual n
      }
    }
  }

  "A Jena Model" should {
    "be converted correctly" in {
      model.asRdfGraph(aUrl).rightValue shouldEqual graph
    }
  }

  "A Node" should {
    "be converted correctly" when {
      "node is an IriNode" in {
        val node: Node = IriNode(xsd.integer)
        val expected   = ResourceFactory.createResource(xsd.integer.asString)
        node.asJena shouldEqual expected
        IriNode(xsd.integer).asJena shouldEqual expected
        (IriNode(xsd.integer): IriOrBNode).asJena shouldEqual expected
      }
      "node is an BNode" in {
        val node: Node = b"blah"
        val expected   = new ResourceImpl(AnonId.create("blah"))
        node.asJena shouldEqual expected
        b"blah".asJena shouldEqual expected
        (b"blah": IriOrBNode).asJena shouldEqual expected
      }
      "node is a literal" in {
        val cases = List(
          ResourceFactory.createLangLiteral("a", "en-US")                 -> Literal("a", LanguageTag("en-US").rightValue),
          ResourceFactory.createStringLiteral("a")                        -> Literal("a"),
          ResourceFactory.createTypedLiteral("1", XSDDatatype.XSDinteger) -> Literal(1),
          ResourceFactory.createTypedLiteral("1.0", XSDDatatype.XSDfloat) -> Literal(1f),
          ResourceFactory.createLangLiteral("1", "en")                    -> Literal("1", LanguageTag("en").rightValue)
        )
        forAll(cases) {
          case (jl, l) =>
            (l: Node).asJena shouldEqual jl
            l.asJena shouldEqual jl
        }
      }
    }
  }

  "A Graph" should {
    "be converted correctly" in {
      graph.asJena isIsomorphicWith model shouldEqual true
    }
  }

}
