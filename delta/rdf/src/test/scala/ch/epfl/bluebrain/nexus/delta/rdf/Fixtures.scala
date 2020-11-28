package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit._
import io.circe.Json
import monix.execution.schedulers.CanBlock
import org.scalatest.OptionValues

trait Fixtures
    extends TestHelpers
    with CirceLiteral
    with OptionValues
    with IOValues
    with EitherValuable
    with TestMatchers {

  val iri = iri"http://nexus.example.com/john-doé"

  // format: off
  val remoteContexts: Map[Iri, Json] =
    Map(
      iri"http://example.com/cöntéxt/0"  -> json"""{"@context": {"deprecated": {"@id": "http://schema.org/deprecated", "@type": "http://www.w3.org/2001/XMLSchema#boolean"} }}""",
      iri"http://example.com/cöntéxt/1"  -> json"""{"@context": ["http://example.com/cöntéxt/11", "http://example.com/cöntéxt/12"] }""",
      iri"http://example.com/cöntéxt/11" -> json"""{"@context": {"birthDate": "http://schema.org/birthDate"} }""",
      iri"http://example.com/cöntéxt/12" -> json"""{"@context": {"Other": "http://schema.org/Other"} }""",
      iri"http://example.com/cöntéxt/2"  -> json"""{"@context": {"integerAlias": "http://www.w3.org/2001/XMLSchema#integer", "type": "@type"} }""",
      iri"http://example.com/cöntéxt/3"  -> json"""{"@context": {"customid": {"@type": "@id"} } }"""
    )
  // format: on

  implicit val remoteResolution: RemoteContextResolution = RemoteContextResolution.fixed(remoteContexts.toSeq: _*)
  implicit val pm: CanBlock                              = CanBlock.permit

  object vocab {
    val value                  = iri"http://example.com/"
    def +(string: String): Iri = iri"$value$string"
  }

  object base {
    val value                  = iri"http://nexus.example.com/"
    def +(string: String): Iri = iri"$value$string"
  }

  def bNode(graph: Graph): BNode =
    BNode.unsafe(
      graph
        .find { case (s, p, _) => s == subject(graph.rootNode) && p == predicate(vocab + "address") }
        .map(_._3.asNode().getBlankNodeLabel)
        .value
    )

}

object Fixtures extends Fixtures
