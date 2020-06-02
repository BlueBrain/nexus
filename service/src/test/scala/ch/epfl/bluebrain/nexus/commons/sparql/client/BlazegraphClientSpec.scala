package ch.epfl.bluebrain.nexus.commons.sparql.client

import java.io.File
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClientFixture._
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.{SparqlClientError, SparqlServerError}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults._
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlWriteQuery._
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Node.IriOrBNode
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax._
import ch.epfl.bluebrain.nexus.rdf.jena.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.jena.Jena
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util.{ActorSystemFixture, CirceEq, EitherValues, IOValues, Randomness, Resources}
import com.bigdata.rdf.sail.webapp.NanoSparqlServer
import io.circe.{Json, Printer}
import io.circe.parser._
import io.circe.syntax._
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

//noinspection TypeAnnotation
class BlazegraphClientSpec
    extends ActorSystemFixture("BlazegraphClientSpec")
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with EitherValues
    with BeforeAndAfterAll
    with Randomness
    with Resources
    with OptionValues
    with CirceEq
    with Eventually {

  implicit private val printer = Printer.noSpaces.copy(dropNullValues = true)

  private val port = freePort()

  private val server = {
    System.setProperty("jetty.home", getClass.getResource("/war").toExternalForm)
    NanoSparqlServer.newInstance(port, null, null)
  }

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 500.milliseconds)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Try(FileUtils.forceDelete(new File("bigdata.jnl")))
    server.start()
  }

  override protected def afterAll(): Unit = {
    server.stop()
    super.afterAll()
  }

  implicit private val ec          = system.dispatcher
  implicit private val timer       = IO.timer(ec)
  implicit private val uc          = untyped[IO]
  implicit private val jc          = withUnmarshaller[IO, SparqlResults]
  implicit private val retryConfig = RetryStrategyConfig("once", 100.millis, 0.millis, 0, 0.millis)

  "A BlazegraphClient" should {
    def client(ns: String) = BlazegraphClient[IO](s"http://$localhost:$port/blazegraph", ns, None)

    "fetch the service description" in new BlazegraphClientFixture {
      client(genString()).serviceDescription.ioValue shouldEqual ServiceDescription("blazegraph", "2.1.5")
    }

    "verify if a namespace exists" in new BlazegraphClientFixture {
      client(namespace).namespaceExists.ioValue shouldEqual false
    }

    "create an index" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue shouldEqual true
      cl.namespaceExists.ioValue shouldEqual true
      cl.createNamespace(properties()).ioValue shouldEqual false
    }

    "create index with wrong payload" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties("/commons/sparql/wrong.properties")).failed[SparqlServerError]
    }

    "delete an index" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.deleteNamespace.ioValue shouldEqual false
      cl.createNamespace(properties()).ioValue shouldEqual true
      cl.deleteNamespace.ioValue shouldEqual true
    }

    "create a new named graph" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      cl.replace(graph, load(id, label, value)).ioValue
      cl.copy(namespace = namespace).triples(graph) should have size 2
      cl.triples() should have size 2
    }

    "drop a named graph" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      cl.replace(graph, load(id, label, value)).ioValue
      cl.drop(graph).ioValue
      cl.triples() shouldBe empty
    }

    "replace a named graph" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      cl.replace(graph, load(id, label, value)).ioValue
      val triple: Triple = ((url"http://example/com/$id", owl.sameAs, """{"key": "value"}"""))
      cl.replace(graph, load(id, label, value + "-updated") + triple).ioValue
      cl.triples(graph).map(_._3) should contain theSameElementsAs Set(
        label,
        value + "-updated",
        """{"key": "value"}"""
      )
      cl.triples().map(_._3) should contain theSameElementsAs Set(label, value + "-updated", """{"key": "value"}""")
    }

    "run bulk operation" in new BlazegraphClientFixture {
      val id2: String    = genString()
      val label2: String = genString()
      val value2: String = genString()
      val graph2: Uri    = s"http://$localhost:8080/graphs/${genString()}"

      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      cl.bulk(Seq(replace(graph, load(id, label, value)), replace(graph2, load(id2, label2, value2)))).ioValue
      cl.triples(graph).map(_._3) should contain theSameElementsAs Set(label, value)
      cl.triples(graph2).map(_._3) should contain theSameElementsAs Set(label2, value2)
      cl.triples().map(_._3) should contain theSameElementsAs Set(label, value) ++ Set(label2, value2)
    }

    "return the JSON response from query" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      cl.replace(graph, load(id, label, value)).ioValue
      val expected = jsonContentOf(
        "/commons/sparql/sparql-json.json",
        Map(quote("{id}") -> id, quote("{label}") -> label, quote("{value}") -> value)
      )
      val result = cl.queryRaw(s"SELECT * WHERE { GRAPH <$graph> { ?s ?p ?o } }").ioValue.asJson
      result.asObject.value("head").value.removeKeys("link") shouldEqual expected.asObject.value("head").value
      val bindings =
        result.asObject.value("results").value.asObject.value("bindings").value.asArray.value.map(printer.print)
      bindings should contain theSameElementsAs
        expected.asObject.value("results").value.asObject.value("bindings").value.asArray.value.map(printer.print)
    }

    "fail the query" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      cl.replace(graph, load(id, label, value)).ioValue
      cl.queryRaw(s"SELECT somethingwrong").failed[SparqlClientError]
    }

    "patch a named graph removing matching predicates" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      val json = parse(
        s"""
           |{
           |  "@context": {
           |    "label": "http://www.w3.org/2000/01/rdf-schema#label",
           |    "schema": "http://schema.org/",
           |    "nested": "http://localhost/nested/"
           |  },
           |  "@id": "http://localhost/$id",
           |  "label": "$label-updated",
           |  "nested": {
           |     "schema:name": "name",
           |     "schema:title": "title"
           |  }
           |}""".stripMargin
      ).rightValue
      cl.replace(graph, load(id, label, value)).ioValue
      val strategy = PatchStrategy.removePredicates(
        Set(
          "http://schema.org/value",
          "http://www.w3.org/2000/01/rdf-schema#label"
        )
      )
      cl.patch(graph, json.asRdfGraph(url"http://localhost/$id").rightValue, strategy).ioValue
      cl.triples() should have size 4
      val results = cl.triples(graph)
      results should have size 4
      results.map(_._2).toSet should contain theSameElementsAs Set(
        "http://www.w3.org/2000/01/rdf-schema#label",
        "http://schema.org/name",
        "http://localhost/nested/",
        "http://schema.org/title"
      )
      results.map(_._3).toSet should contain allOf ("name", "title", s"$label-updated")
    }

    "patch a named graph retaining matching predicates" in new BlazegraphClientFixture {
      val cl = client(namespace)
      cl.createNamespace(properties()).ioValue
      val json = parse(
        s"""
           |{
           |  "@context": {
           |    "label": "http://www.w3.org/2000/01/rdf-schema#label",
           |    "schema": "http://schema.org/",
           |    "nested": "http://localhost/nested/"
           |  },
           |  "@id": "http://localhost/$id",
           |  "label": "$label-updated",
           |  "nested": {
           |     "schema:name": "name",
           |     "schema:title": "title"
           |  }
           |}
           """.stripMargin
      ).rightValue
      cl.replace(graph, load(id, label, value)).ioValue
      val strategy = PatchStrategy.removeButPredicates(Set("http://schema.org/value"))
      cl.patch(graph, json.asRdfGraph(url"http://localhost/$id").rightValue, strategy).ioValue
      val results = cl.triples(graph)
      results should have size 5
      results.map(_._3).toSet should contain allOf (label + "-updated", value, "name", "title")
    }
  }

  implicit class BlazegraphClientOps(cl: BlazegraphClient[IO])(implicit ec: ExecutionContext) {
    private def triplesFor(query: String) =
      cl.queryRaw(query).map {
        case SparqlResults(_, Bindings(mapList), _) =>
          mapList.map { triples => (triples("s").value, triples("p").value, triples("o").value) }
      }

    def triples(graph: Uri): List[(String, String, String)] =
      triplesFor(s"SELECT * WHERE { GRAPH <$graph> { ?s ?p ?o } }").ioValue

    def triples(): List[(String, String, String)] =
      triplesFor("SELECT * { ?s ?p ?o }").ioValue
  }

  private def load(id: String, label: String, value: String): Graph =
    jsonContentOf(
      "/commons/sparql/ld.json",
      Map(quote("{{ID}}") -> id, quote("{{LABEL}}") -> label, quote("{{VALUE}}") -> value)
    ).asRdfGraph(url"http://localhost/$id").rightValue

  implicit private class AsGraph(json: Json) {
    def asRdfGraph(root: IriOrBNode): Either[String, Graph] =
      Jena.parse(json.noSpaces).flatMap(_.asRdfGraph(root))
  }
}
