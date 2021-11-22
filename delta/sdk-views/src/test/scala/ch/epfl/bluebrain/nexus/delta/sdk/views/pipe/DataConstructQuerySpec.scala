package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery

class DataConstructQuerySpec extends PipeBaseSpec {

  "DataConstructQuery" should {

    "reject an invalid config" in {
      DataConstructQuery.pipe.parseAndRun(Some(ExpandedJsonLd.empty), sampleData).rejected
    }

    "transform the data according to the query" in {
      val query  = SparqlConstructQuery.unsafe("""prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/>
                    |prefix schema: <http://schema.org/>
                    |
                    |CONSTRUCT {
                    |  ?person 	        schema:name          ?name ;
                    |                   nxv:number           ?number ;
                    |} WHERE {
                    |  ?person 	        schema:name          ?name ;
                    |                   nxv:number           ?number ;
                    |}""".stripMargin)
      val name   = predicate(schema + "name")
      val number = predicate(nxv + "number")
      DataConstructQuery.pipe
        .parseAndRun(DataConstructQuery(query), sampleData)
        .accepted
        .value shouldEqual sampleData.copy(
        types = Set.empty,
        graph = sampleData.graph.filter { case (_, p, _) =>
          p == name || p == number
        }
      )
    }
  }
}
