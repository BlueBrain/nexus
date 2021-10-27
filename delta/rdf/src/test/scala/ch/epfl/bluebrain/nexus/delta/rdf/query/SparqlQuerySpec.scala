package ch.epfl.bluebrain.nexus.delta.rdf.query

import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SparqlQuerySpec extends AnyWordSpec with Matchers with EitherValuable {

  val validSparql = """SELECT ?s ?p ?o WHERE { ?s ?p ?o }"""

  val validConstruct = """
              prefix example: <http://example.com/>
              prefix schema: <http://schema.org/>

              CONSTRUCT {
                ?person 	        a                       ?type ;
                                 schema:name             ?name ;
                                 schema:birthDate        ?birthDate ;
              } WHERE {
                ?person 	        a                       ?type ;
                                schema:name             ?name ;
                                 schema:birthDate        ?birthDate ;
              }
              """

  "SparqlQuery" should {

    "give a construct query when a valid one is passed to apply" in {
      SparqlQuery(validConstruct) shouldEqual SparqlConstructQuery.unsafe(validConstruct)
    }
  }

  "SparqlConstructQuery" should {
    "give a construct query when a valid one is passed" in {
      SparqlConstructQuery(validConstruct).rightValue shouldEqual SparqlConstructQuery.unsafe(validConstruct)
    }

    "raise an error when a non-construct query is passed" in {
      SparqlConstructQuery(
        validSparql
      ).leftValue shouldEqual "The provided query is a valid SPARQL query but not a CONSTRUCT query"
    }

    "raise an error when an invalid query is passed" in {
      SparqlConstructQuery("xxx").leftValue shouldEqual "The provided query is not a valid SPARQL query"
    }
  }
}
