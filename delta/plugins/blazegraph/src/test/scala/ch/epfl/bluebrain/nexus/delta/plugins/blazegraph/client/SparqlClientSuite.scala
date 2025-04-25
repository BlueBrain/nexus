package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.SparqlClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.PatchStrategy.{keepPredicates, removePredicates}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.SparqlQueryError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse.SparqlResultsResponse
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Bindings
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery.replace
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.mu.{NexusSuite, StringAssertions}
import io.circe.Json
import org.apache.jena.graph.Graph
import org.apache.jena.query.DatasetFactory
import org.apache.jena.riot.{Lang, RDFParser}
import org.http4s.Uri

import scala.xml.Elem

abstract class SparqlClientSuite extends NexusSuite with SparqlClientSetup.Fixture with StringAssertions {

  def client: SparqlClient

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit private val api: JsonLdApi               = TitaniumJsonLdApi.strict
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.never

  private def jsonToNtriples(json: Json) = ExpandedJsonLd(json).flatMap(_.toGraph).flatMap(_.toNTriples)

  private def nTriples(id: String = genString(), label: String = genString(), value: String = genString()) =
    loader
      .jsonContentOf("sparql/example.jsonld", "id" -> id, "label" -> label, "value" -> value)
      .flatMap(jsonToNtriples)

  private def nTriplesNested(id: String, label: String, name: String, title: String) =
    loader
      .jsonContentOf("sparql/example-nested.jsonld", "id" -> id, "label" -> label, "name" -> name, "title" -> title)
      .flatMap(jsonToNtriples)

  private def expectedResult(id: String, label: String, value: String) =
    Set(
      (s"http://localhost/$id", "http://schema.org/value", value),
      (s"http://localhost/$id", "http://www.w3.org/2000/01/rdf-schema#label", label)
    )

  private val rootUri = Uri.unsafeFromString(baseUri.base.toString())

  private lazy val graphId = rootUri / "graphs" / "myid"

  private val constructQuery = SparqlConstructQuery.unsafe("CONSTRUCT {?s ?p ?o} WHERE { ?s ?p ?o }")

  private def jsonLdResults(indices: Set[String]) =
    client.query(indices, constructQuery, SparqlJsonLd).map(_.value)

  private def triplesResults(indices: Set[String]) =
    client.query(indices, constructQuery, SparqlNTriples).map(_.value)

  private def xmlRdfResults(indices: Set[String]) =
    client.query(indices, constructQuery, SparqlRdfXml).map(_.value.head.asInstanceOf[Elem])

  private val selectQuery = SparqlQuery("SELECT * WHERE { ?s ?p ?o }")

  private def xmlResults(namespaces: Set[String]) =
    client.query(namespaces, selectQuery, SparqlResultsXml).map(_.value.head.asInstanceOf[Elem])

  private def triplesSparqlResults(namespace: String, graph: Uri) =
    triplesForSparqlResults(namespace, s"SELECT * WHERE { GRAPH <$graph> { ?s ?p ?o } }")

  private def triplesSparqlResults(namespace: String) =
    triplesForSparqlResults(namespace, "SELECT * { ?s ?p ?o }")

  private def triplesForSparqlResults(namespace: String, query: String) =
    client
      .query(Set(namespace), SparqlQuery(query), SparqlResultsJson)
      .map { case SparqlResultsResponse(SparqlResults(_, Bindings(mapList), _)) =>
        mapList.map { triples => (triples("s").value, triples("p").value, triples("o").value) }
      }
      .map(_.toSet)

  private def parseAsGraph(elem: Elem): Graph = parseAsGraph(elem.toString())

  private def parseAsGraph(string: String): Graph = {
    val g = DatasetFactory.create().asDatasetGraph()
    RDFParser.create().fromString(string).lang(Lang.RDFXML).parse(g)
    g.getDefaultGraph
  }

  private def trimFormatting(elem: Elem): String = trimFormatting(elem.toString())

  private def trimFormatting(string: String): String =
    string.replaceAll("(?m)^\\s+", "").replaceAll("\n", "")

  test("Verify that a namespace does not exist") {
    client.existsNamespace("some").assertEquals(false)
  }

  test("Create a namespace") {
    client.createNamespace("some").assertEquals(true)
  }

  test("Attempt to create a namespace a second time") {
    client.createNamespace("some").assertEquals(false)
  }

  test("Create a named graph") {
    val namespace = genString()
    for {
      _            <- client.createNamespace(namespace)
      data         <- nTriples()
      _            <- client.replace(namespace, graphId, data)
      graphResults <- triplesSparqlResults(namespace, graphId)
      allResults   <- triplesSparqlResults(namespace)
    } yield {
      assertEquals(graphResults.size, 2)
      assertEquals(graphResults, allResults)
    }
  }

  test("Drop a named graph") {
    val namespace = genString()
    for {
      _          <- client.createNamespace(namespace)
      data       <- nTriples()
      _          <- client.replace(namespace, graphId, data)
      _          <- client.drop(namespace, graphId)
      allResults <- triplesSparqlResults(namespace)
    } yield {
      assert(allResults.isEmpty, "After deleting the graph, there should not be any data left.")
    }
  }

  test("Replace a named graph") {
    val namespace = genString()
    for {
      _            <- client.createNamespace(namespace)
      originalData <- nTriples(id = "myid", "a", "b")
      _            <- client.replace(namespace, graphId, originalData)
      updatedData  <- nTriples(id = "myid", "a", "b-updated")
      _            <- client.replace(namespace, graphId, updatedData)
      graphResults <- triplesSparqlResults(namespace, graphId)
      allResults   <- triplesSparqlResults(namespace)
    } yield {
      val expected = expectedResult("myid", "a", "b-updated")
      assertEquals(graphResults, expected)
      assertEquals(allResults, expected)
    }
  }

  test("Query as JSON-LD, N-Triples and XML") {
    val namespace1 = genString()
    val namespace2 = genString()
    val namespaces = Set(namespace1, namespace2)

    def populateData = List(namespace1, namespace2).zipWithIndex.traverse { case (namespace, index) =>
      val graphId = rootUri / "graphs" / s"myid-$index"
      client.createNamespace(namespace) >>
        nTriples(id = s"myid-$index", s"a-$index", s"b-$index").flatMap { data =>
          client.replace(namespace, graphId, data)
        }
    }

    for {
      _               <- populateData
      // JSON-LD
      expectedJsonLd  <- loader.jsonContentOf("sparql/results/json-ld-result.json")
      _               <- jsonLdResults(namespaces).assertEquals(expectedJsonLd)
      // N-Triples
      expectedTriples <- loader.contentOf("sparql/results/ntriples-result.nt")
      _               <- triplesResults(namespaces).map(_.value).map(_.equalLinesUnordered(expectedTriples))
      // RDF/XML
      expectedXmlRdf  <- loader.contentOf("sparql/results/query-results-construct.xml").map(parseAsGraph)
      obtainedXmlRdf  <- xmlRdfResults(namespaces).map(parseAsGraph)
      _                = assert(expectedXmlRdf.isIsomorphicWith(obtainedXmlRdf))
      // SPARQL results / XML
      expectedXml     <- loader.contentOf("sparql/results/query-results.xml").map(trimFormatting)
      _               <- xmlResults(namespaces).map(trimFormatting).assertEquals(expectedXml)
    } yield ()
  }

  test("Run bulk operation") {
    val (id, label, value)    = (genString(), genString(), genString())
    val (id2, label2, value2) = (genString(), genString(), genString())
    val graph: Uri            = rootUri / "graphs" / genString()
    val graph2: Uri           = rootUri / "graphs" / genString()
    val namespace             = genString()
    for {
      _          <- client.createNamespace(namespace)
      data1      <- nTriples(id, label, value)
      data2      <- nTriples(id2, label2, value2)
      bulk        = List(replace(graph, data1), replace(graph2, data2))
      _          <- client.bulk(namespace, bulk)
      _          <- triplesSparqlResults(namespace, graph).assertEquals(expectedResult(id, label, value))
      _          <- triplesSparqlResults(namespace, graph2).assertEquals(expectedResult(id2, label2, value2))
      expectedAll = expectedResult(id, label, value) ++ expectedResult(id2, label2, value2)
      _          <- triplesSparqlResults(namespace).assertEquals(expectedAll)
      _          <- client.count(namespace).assertEquals(4L)
    } yield ()
  }

  test("Fail running invalid query") {
    val namespace    = genString()
    val invalidQuery = SparqlQuery("SELECT somethingwrong")
    client.createNamespace(namespace) >>
      client.query(Set(namespace), invalidQuery, SparqlResultsJson).intercept[SparqlQueryError]
  }

  test("Patch a named graph removing the matching predicates") {
    val namespace     = genString()
    val patchStrategy = removePredicates(Set("http://schema.org/value", "http://www.w3.org/2000/01/rdf-schema#label"))
    for {
      _            <- client.createNamespace(namespace)
      originalData <- nTriples(id = "myid", "a", "b")
      _            <- client.replace(namespace, graphId, originalData)
      patchData    <- nTriplesNested("myid", "b", "name", "title")
      _            <- client.patch(namespace, graphId, patchData, patchStrategy)
      result       <- triplesSparqlResults(namespace)
    } yield {
      val expected = Set(
        (s"http://localhost/myid", "http://www.w3.org/2000/01/rdf-schema#label", "b"),
        ("http://localhost/myid", "http://localhost/nested/", "http://localhost/nested"),
        (s"http://localhost/nested", "http://schema.org/name", "name"),
        (s"http://localhost/nested", "http://schema.org/title", "title")
      )
      assertEquals(result, expected)
    }
  }

  test("Patch a named graph keeping the matching predicates") {
    val namespace     = genString()
    val patchStrategy = keepPredicates(Set("http://schema.org/value"))
    for {
      _            <- client.createNamespace(namespace)
      originalData <- nTriples(id = "myid", "lb-a", "value")
      _            <- client.replace(namespace, graphId, originalData)
      patchData    <- nTriplesNested("myid", "lb-b", "name", "title")
      _            <- client.patch(namespace, graphId, patchData, patchStrategy)
      result       <- triplesSparqlResults(namespace)
    } yield {
      val expected = expectedResult("myid", "lb-b", "value") ++ Set(
        ("http://localhost/myid", "http://localhost/nested/", "http://localhost/nested"),
        (s"http://localhost/nested", "http://schema.org/name", "name"),
        (s"http://localhost/nested", "http://schema.org/title", "title")
      )
      assertEquals(result, expected)
    }
  }

}
