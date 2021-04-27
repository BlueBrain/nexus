package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.PatchStrategy._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.WrappedHttpClientError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse.SparqlResultsResponse
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{SparqlJsonLd, SparqlNTriples, SparqlRdfXml, SparqlResultsJson, SparqlResultsXml}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Bindings
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery.replace
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.{HttpClientStatusError, HttpServerStatusError}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers, TestMatchers}
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, DoNotDiscover, Inspectors, Suite}

import scala.xml.Elem

@DoNotDiscover
class BlazegraphClientSpec
    extends TestKit(ActorSystem("BlazegraphClientSpec"))
    with Suite
    with AnyWordSpecLike
    with Matchers
    with ConfigFixtures
    with EitherValuable
    with CancelAfterFailure
    with TestHelpers
    with Eventually
    with Inspectors
    with TestMatchers
    with IOValues {

  implicit private val sc: Scheduler                = Scheduler.global
  implicit private val httpCfg: HttpClientConfig    = httpClientConfig
  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.never

  private val endpoint = blazegraphHostConfig.endpoint
  private val client   = BlazegraphClient(HttpClient(), endpoint, None)
  private val graphId  = endpoint / "graphs" / "myid"

  private val properties = propertiesOf("/sparql/index.properties")

  private def nTriples(id: String = genString(), label: String = genString(), value: String = genString()) = {
    val json = jsonContentOf("/sparql/example.jsonld", "id" -> id, "label" -> label, "value" -> value)
    ExpandedJsonLd(json).accepted.toGraph.flatMap(_.toNTriples).rightValue
  }

  private def nTriplesNested(id: String, label: String, name: String, title: String) = {
    val json =
      jsonContentOf("/sparql/example-nested.jsonld", "id" -> id, "label" -> label, "name" -> name, "title" -> title)
    ExpandedJsonLd(json).accepted.toGraph.flatMap(_.toNTriples).rightValue
  }

  private def expectedResult(id: String, label: String, value: String) =
    Set(
      (s"http://localhost/$id", "http://schema.org/value", value),
      (s"http://localhost/$id", "http://www.w3.org/2000/01/rdf-schema#label", label)
    )

  private def jsonLdResults(indices: Set[String]): Json =
    client
      .query(indices, SparqlConstructQuery(s"CONSTRUCT {?s ?p ?o} WHERE { ?s ?p ?o }").rightValue, SparqlJsonLd)
      .accepted
      .value

  private def triplesResults(indices: Set[String]): NTriples =
    client
      .query(indices, SparqlConstructQuery(s"CONSTRUCT {?s ?p ?o} WHERE { ?s ?p ?o }").rightValue, SparqlNTriples)
      .accepted
      .value

  private def xmlRdfResults(indices: Set[String]): Elem =
    client
      .query(indices, SparqlConstructQuery(s"CONSTRUCT {?s ?p ?o} WHERE { ?s ?p ?o }").rightValue, SparqlRdfXml)
      .accepted
      .value
      .head
      .asInstanceOf[Elem]

  private def xmlResults(indices: Set[String]): Elem =
    client
      .query(indices, SparqlQuery(s"SELECT * WHERE { ?s ?p ?o }"), SparqlResultsXml)
      .accepted
      .value
      .head
      .asInstanceOf[Elem]

  private def triplesSparqlResults(index: String, graph: Uri): Set[(String, String, String)] =
    triplesForSparqlResults(index, s"SELECT * WHERE { GRAPH <$graph> { ?s ?p ?o } }")

  private def triplesSparqlResults(index: String): Set[(String, String, String)] =
    triplesForSparqlResults(index, "SELECT * { ?s ?p ?o }")

  private def triplesForSparqlResults(index: String, query: String): Set[(String, String, String)] =
    client
      .query(Set(index), SparqlQuery(query), SparqlResultsJson)
      .map { case SparqlResultsResponse(SparqlResults(_, Bindings(mapList), _)) =>
        mapList.map { triples => (triples("s").value, triples("p").value, triples("o").value) }
      }
      .accepted
      .toSet

  private def trimFormatting(string: String) =
    string.replaceAll("(?m)^\\s+", "").replaceAll("\n", "")

  "A Blazegraph Client" should {

    "fetch the service description" in {
      client.serviceDescription.accepted shouldEqual ServiceDescription(Name.unsafe("blazegraph"), "2.1.5")
    }

    "verify a namespace does not exist" in {
      client.existsNamespace("some").accepted shouldEqual false
    }

    "create namespace" in {
      client.createNamespace("some", properties).accepted shouldEqual true
    }

    "attempt to create namespace a second time" in {
      client.createNamespace("some", Map.empty).accepted shouldEqual false
    }

    "attempt to create a namespace with wrong payload" in {
      val props = propertiesOf("/sparql/wrong.properties")
      val err   = client.createNamespace("other", props).rejectedWith[WrappedHttpClientError]
      err.http shouldBe a[HttpServerStatusError]
    }

    "create a new named graph" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples()).accepted
      triplesSparqlResults(index, graphId) should have size 2
      triplesSparqlResults(index) should have size 2
      triplesSparqlResults(index, graphId) shouldEqual triplesSparqlResults(index)
    }

    "drop a named graph" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples()).accepted
      client.drop(index, graphId).accepted
      triplesSparqlResults(index) shouldBe empty
    }

    "replace a named graph" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples(id = "myid", "a", "b")).accepted
      client.replace(index, graphId, nTriples(id = "myid", "a", "b-updated")).accepted
      triplesSparqlResults(index, graphId) shouldEqual expectedResult("myid", "a", "b-updated")
      triplesSparqlResults(index) shouldEqual expectedResult("myid", "a", "b-updated")
    }

    "query as JSON-LD, N-Triples and XML" in {
      val index1 = genString()
      val index2 = genString()
      forAll(List(index1, index2).zipWithIndex) { case (index, id) =>
        val graphId = endpoint / "graphs" / s"myid-$id"
        client.createNamespace(index, properties).accepted
        client.replace(index, graphId, nTriples(id = s"myid-$id", s"a-$id", s"b-$id")).accepted
      }
      eventually {
        jsonLdResults(Set(index1, index2)) shouldEqual jsonContentOf("sparql/results/json-ld-result.json")
      }

      triplesResults(Set(index1, index2)).value should
        equalLinesUnordered(contentOf("sparql/results/ntriples-result.nt"))

      trimFormatting(xmlRdfResults(Set(index1, index2)).toString) shouldEqual
        trimFormatting(contentOf("sparql/results/query-results-construct.xml"))

      trimFormatting(xmlResults(Set(index1, index2)).toString) shouldEqual
        trimFormatting(contentOf("sparql/results/query-results.xml"))
    }

    "run bulk operation" in {
      val (id, label, value)    = (genString(), genString(), genString())
      val (id2, label2, value2) = (genString(), genString(), genString())
      val graph: Uri            = endpoint / "graphs" / genString()
      val graph2: Uri           = endpoint / "graphs" / genString()
      val index                 = genString()
      val seq                   = List(replace(graph, nTriples(id, label, value)), replace(graph2, nTriples(id2, label2, value2)))
      client.createNamespace(index, properties).accepted
      client.bulk(index, seq).accepted
      triplesSparqlResults(index, graph) shouldEqual expectedResult(id, label, value)
      triplesSparqlResults(index, graph2) shouldEqual expectedResult(id2, label2, value2)
      triplesSparqlResults(index) shouldEqual expectedResult(id, label, value) ++ expectedResult(id2, label2, value2)
    }

    "fail the query" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client
        .query(Set(index), SparqlQuery("SELECT somethingwrong"), SparqlResultsJson)
        .rejectedWith[WrappedHttpClientError]
        .http shouldBe
        a[HttpClientStatusError]
    }

    "patch a named graph removing the matching predicates" in {
      val index    = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples(id = "myid", "a", "b")).accepted
      val strategy = removePredicates(Set("http://schema.org/value", "http://www.w3.org/2000/01/rdf-schema#label"))
      client.patch(index, graphId, nTriplesNested("myid", "b", "name", "title"), strategy).accepted
      triplesSparqlResults(index) shouldEqual Set(
        (s"http://localhost/myid", "http://www.w3.org/2000/01/rdf-schema#label", "b"),
        ("http://localhost/myid", "http://localhost/nested/", "http://localhost/nested"),
        (s"http://localhost/nested", "http://schema.org/name", "name"),
        (s"http://localhost/nested", "http://schema.org/title", "title")
      )
    }

    "patch a named graph keeping the matching predicates" in {
      val index    = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples(id = "myid", "lb-a", "value")).accepted
      val strategy = keepPredicates(Set("http://schema.org/value"))
      client.patch(index, graphId, nTriplesNested("myid", "lb-b", "name", "title"), strategy).accepted
      triplesSparqlResults(index) shouldEqual expectedResult("myid", "lb-b", "value") ++ Set(
        ("http://localhost/myid", "http://localhost/nested/", "http://localhost/nested"),
        (s"http://localhost/nested", "http://schema.org/name", "name"),
        (s"http://localhost/nested", "http://schema.org/title", "title")
      )
    }
  }
}
