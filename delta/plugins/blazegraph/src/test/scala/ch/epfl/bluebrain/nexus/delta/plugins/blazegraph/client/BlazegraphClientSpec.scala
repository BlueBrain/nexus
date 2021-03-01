package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.blazegraphHostConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.PatchStrategy._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.WrappedHttpClientError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Bindings
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlWriteQuery.replace
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.{HttpClientStatusError, HttpServerStatusError}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{DoNotDiscover, Suite}

@DoNotDiscover
class BlazegraphClientSpec
    extends TestKit(ActorSystem("BlazegraphClientSpec"))
    with Suite
    with AnyWordSpecLike
    with Matchers
    with ConfigFixtures
    with EitherValuable
    with TestHelpers
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

  private def triples(index: String, graph: Uri): Set[(String, String, String)] =
    triplesFor(index, s"SELECT * WHERE { GRAPH <$graph> { ?s ?p ?o } }")

  private def triples(index: String): Set[(String, String, String)] =
    triplesFor(index, "SELECT * { ?s ?p ?o }")

  private def triplesFor(index: String, query: String): Set[(String, String, String)] =
    client
      .query(Set(index), SparqlQuery(query))
      .map { case SparqlResults(_, Bindings(mapList), _) =>
        mapList.map { triples => (triples("s").value, triples("p").value, triples("o").value) }
      }
      .accepted
      .toSet

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
      triples(index, graphId) should have size 2
      triples(index) should have size 2
      triples(index, graphId) shouldEqual triples(index)
    }

    "drop a named graph" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples()).accepted
      client.drop(index, graphId).accepted
      triples(index) shouldBe empty
    }

    "replace a named graph" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples(id = "myid", "a", "b")).accepted
      client.replace(index, graphId, nTriples(id = "myid", "a", "b-updated")).accepted
      triples(index, graphId) shouldEqual expectedResult("myid", "a", "b-updated")
      triples(index) shouldEqual expectedResult("myid", "a", "b-updated")
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
      triples(index, graph) shouldEqual expectedResult(id, label, value)
      triples(index, graph2) shouldEqual expectedResult(id2, label2, value2)
      triples(index) shouldEqual expectedResult(id, label, value) ++ expectedResult(id2, label2, value2)
    }

    "fail the query" in {
      val index = genString()
      client.createNamespace(index, properties).accepted
      client.query(Set(index), SparqlQuery("SELECT somethingwrong")).rejectedWith[WrappedHttpClientError].http shouldBe
        a[HttpClientStatusError]
    }

    "patch a named graph removing the matching predicates" in {
      val index    = genString()
      client.createNamespace(index, properties).accepted
      client.replace(index, graphId, nTriples(id = "myid", "a", "b")).accepted
      val strategy = removePredicates(Set("http://schema.org/value", "http://www.w3.org/2000/01/rdf-schema#label"))
      client.patch(index, graphId, nTriplesNested("myid", "b", "name", "title"), strategy).accepted
      triples(index) shouldEqual Set(
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
      triples(index) shouldEqual expectedResult("myid", "lb-b", "value") ++ Set(
        ("http://localhost/myid", "http://localhost/nested/", "http://localhost/nested"),
        (s"http://localhost/nested", "http://schema.org/name", "name"),
        (s"http://localhost/nested", "http://schema.org/title", "title")
      )
    }
  }
}
