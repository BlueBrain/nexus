package ch.epfl.bluebrain.nexus.commons.sparql.client

import java.util.Properties
import java.util.regex.Pattern.quote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.Materializer
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.{SparqlClientError, SparqlServerError}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults.Bindings
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlWriteQuery._
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Node.{IriNode, IriOrBNode}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.owl
import ch.epfl.bluebrain.nexus.rdf.jena.Jena
import ch.epfl.bluebrain.nexus.rdf.jena.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Json, Printer}
import izumi.distage.model.definition.StandardAxis
import izumi.distage.model.reflection.DIKey
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel
import izumi.distage.testkit.scalatest.DistageSpecScalatest
import org.scalatest.OptionValues
import org.scalatest.matchers.dsl.MatcherWords._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class BlazegraphClientSpec
    extends DistageSpecScalatest[IO]
    with ShouldMatchers
    with Randomness
    with Resources
    with OptionValues
    with DistageSpecSugar
    with CirceEq {

  implicit private val cs: ContextShift[IO]             = IO.contextShift(ExecutionContext.global)
  implicit private val tm: Timer[IO]                    = IO.timer(ExecutionContext.global)
  implicit private val retryConfig: RetryStrategyConfig = RetryStrategyConfig("once", 100.millis, 0.millis, 0, 0.millis)
  implicit private val printer: Printer                 = Printer.noSpaces.copy(dropNullValues = true)

  override protected def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = StandardAxis.testDummyActivation,
      parallelTests = ParallelLevel.Sequential,
      memoizationRoots = Set(DIKey[ClientFactory]),
      moduleOverrides = new DistageModuleDef("BlazegraphClientSpec") {
        include(BlazegraphDockerModule[IO])

        make[HttpClient[IO, HttpResponse]].from { (as: ActorSystem, mt: Materializer) =>
          implicit val a: ActorSystem  = as
          implicit val m: Materializer = mt
          HttpClient.untyped[IO]
        }

        make[HttpClient[IO, SparqlResults]].from { (as: ActorSystem, mt: Materializer, uc: UntypedHttpClient[IO]) =>
          implicit val m: Materializer          = mt
          implicit val u: UntypedHttpClient[IO] = uc
          implicit val ec: ExecutionContext     = as.dispatcher
          HttpClient.withUnmarshaller[IO, SparqlResults]
        }

        make[ClientFactory].from {
          (
              c: BlazegraphDocker.Container,
              as: ActorSystem,
              mt: Materializer,
              uc: UntypedHttpClient[IO],
              sc: HttpClient[IO, SparqlResults]
          ) =>
            implicit val m: Materializer                  = mt
            implicit val u: UntypedHttpClient[IO]         = uc
            implicit val ec: ExecutionContext             = as.dispatcher
            implicit val s: HttpClient[IO, SparqlResults] = sc
            val port                                      = c.availablePorts.first(BlazegraphDocker.primaryPort)
            val base: Uri                                 = s"http://${port.hostV4}:${port.port}/blazegraph"
            new ClientFactory(base)
        }
      },
      configBaseName = "blazegraph-client-test"
    )

  class ClientFactory(base: Uri)(implicit
      mt: Materializer,
      cl: UntypedHttpClient[IO],
      rsJson: HttpClient[IO, SparqlResults],
      ec: ExecutionContext
  ) {

    def create(namespace: String): BlazegraphClient[IO] =
      BlazegraphClient[IO](base, namespace, None)

    def fixture: Fixture = {
      val rand = genString(length = 8)
      Fixture(
        namespace = genString(8),
        rand = rand,
        graph = s"http://localhost:8080/graphs/$rand",
        id = genString(),
        label = genString(),
        value = genString()
      )
    }

    def properties(file: String = "/commons/sparql/index.properties"): IO[Map[String, String]] = {
      import scala.jdk.CollectionConverters._
      IO {
        val props = new Properties()
        props.load(this.getClass.getResourceAsStream(file))
        props.asScala.toMap
      }
    }

    def load(id: String, label: String, value: String): IO[Graph] =
      for {
        json  <- IO {
                   jsonContentOf(
                     "/commons/sparql/ld.json",
                     Map(quote("{{ID}}") -> id, quote("{{LABEL}}") -> label, quote("{{VALUE}}") -> value)
                   )
                 }
        graph <- json.asExceptionalRdfGraph(url"http://localhost/$id")
      } yield graph
  }

  implicit class AsGraph(json: Json) {
    def asRdfGraph(root: IriOrBNode): IO[Either[String, Graph]] =
      IO(Jena.parse(json.noSpaces).flatMap(_.asRdfGraph(root)))

    def asExceptionalRdfGraph(root: IriOrBNode): IO[Graph] =
      asRdfGraph(root).flatMap {
        case Left(err)    => IO.raiseError(new RuntimeException(s"Unexpected error '$err'"))
        case Right(graph) => IO.pure(graph)
      }
  }

  implicit class BlazegraphClientOps(cl: BlazegraphClient[IO]) {
    private def triplesFor(query: String): IO[List[(String, String, String)]] =
      cl.queryRaw(query).map {
        case SparqlResults(_, Bindings(mapList), _) =>
          mapList.map { triples => (triples("s").value, triples("p").value, triples("o").value) }
      }

    def triples(graph: Uri): IO[List[(String, String, String)]] =
      triplesFor(s"SELECT * WHERE { GRAPH <$graph> { ?s ?p ?o } }")

    def triples(): IO[List[(String, String, String)]] =
      triplesFor("SELECT * { ?s ?p ?o }")
  }

  case class Fixture(
      namespace: String,
      rand: String,
      graph: Uri,
      id: String,
      label: String,
      value: String
  )

  "A BlazegraphClient" should {
    "fetch the service description" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        description <- c.serviceDescription
        _            = description shouldEqual ServiceDescription("blazegraph", "2.1.5")
      } yield ()
    }
    "verify if a namespace exists" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        exists <- c.namespaceExists
        _       = exists shouldEqual false
      } yield ()
    }
    "create an index" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props        <- cf.properties()
        created      <- c.createNamespace(props)
        _             = created shouldEqual true
        exists       <- c.namespaceExists
        _             = exists shouldEqual true
        createdAgain <- c.createNamespace(props)
        _             = createdAgain shouldEqual false
      } yield ()
    }

    "create index with wrong payload" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props <- cf.properties("/commons/sparql/wrong.properties")
        _     <- c.createNamespace(props).passWhenErrorType[SparqlServerError]
      } yield ()
    }

    "delete an index" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        deleted <- c.deleteNamespace
        _        = deleted shouldEqual false
        props   <- cf.properties()
        created <- c.createNamespace(props)
        _        = created shouldEqual true
        deleted <- c.deleteNamespace
        _        = deleted shouldEqual true
      } yield ()
    }

    "create a new named graph" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props   <- cf.properties()
        _       <- c.createNamespace(props)
        graph   <- cf.load(f.id, f.label, f.value)
        _       <- c.replace(f.graph, graph)
        triples <- c.triples(f.graph)
        _        = triples should have size 2
        triples <- c.triples()
        _        = triples should have size 2
      } yield ()
    }

    "drop a named graph" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props   <- cf.properties()
        _       <- c.createNamespace(props)
        graph   <- cf.load(f.id, f.label, f.value)
        _       <- c.replace(f.graph, graph)
        _       <- c.drop(f.graph)
        triples <- c.triples()
        _        = triples shouldBe empty
      } yield ()
    }

    "replace a named graph" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props   <- cf.properties()
        _       <- c.createNamespace(props)
        graph   <- cf.load(f.id, f.label, f.value)
        _       <- c.replace(f.graph, graph)
        triple   = (url"http://example/com/${f.id}", owl.sameAs, """{"key": "value"}"""): Triple
        graph   <- cf.load(f.id, f.label, f.value + "-updated")
        _       <- c.replace(f.graph, graph + triple)
        objects <- c.triples(f.graph).map(_.map(_._3))
        _        = objects should contain theSameElementsAs Set(f.label, f.value + "-updated", """{"key": "value"}""")
        objects <- c.triples().map(_.map(_._3))
        _        = objects should contain theSameElementsAs Set(f.label, f.value + "-updated", """{"key": "value"}""")
      } yield ()
    }

    "run bulk operation" in { (cf: ClientFactory) =>
      val id2: String    = genString()
      val label2: String = genString()
      val value2: String = genString()
      val graph2: Uri    = s"http://localhost:8080/graphs/${genString()}"
      val f              = cf.fixture
      val c              = cf.create(f.namespace)
      for {
        props <- cf.properties()
        _     <- c.createNamespace(props)
        g1    <- cf.load(f.id, f.label, f.value)
        g2    <- cf.load(id2, label2, value2)
        r1     = replace(f.graph, g1)
        r2     = replace(graph2, g2)
        bulk   = Seq(r1, r2)
        _     <- c.bulk(bulk)
        o1    <- c.triples(f.graph).map(_.map(_._3))
        _      = o1 should contain theSameElementsAs Set(f.label, f.value)
        o2    <- c.triples(graph2).map(_.map(_._3))
        _      = o2 should contain theSameElementsAs Set(label2, value2)
        o     <- c.triples().map(_.map(_._3))
        _      = o should contain theSameElementsAs Set(f.label, f.value) ++ Set(label2, value2)
      } yield ()
    }

    "return the JSON response from query" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props           <- cf.properties()
        _               <- c.createNamespace(props)
        graph           <- cf.load(f.id, f.label, f.value)
        _               <- c.replace(f.graph, graph)
        expected        <- IO(
                             jsonContentOf(
                               "/commons/sparql/sparql-json.json",
                               Map(quote("{id}") -> f.id, quote("{label}") -> f.label, quote("{value}") -> f.value)
                             )
                           )
        result          <- c.queryRaw(s"SELECT * WHERE { GRAPH <${f.graph}> { ?s ?p ?o } }")
        expectedResult   = expected.asObject
                             .value("head")
                             .value
        _                = result.asJson.asObject.value("head").value.removeKeys("link") shouldEqual expectedResult
        bindings         = result.asJson.asObject
                             .value("results")
                             .value
                             .asObject
                             .value("bindings")
                             .value
                             .asArray
                             .value
                             .map(printer.print)
        expectedBindings =
          expected.asObject.value("results").value.asObject.value("bindings").value.asArray.value.map(printer.print)
        _                = bindings should contain theSameElementsAs expectedBindings
      } yield ()
    }

    "fail the query" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props <- cf.properties()
        _     <- c.createNamespace(props)
        graph <- cf.load(f.id, f.label, f.value)
        _     <- c.replace(f.graph, graph)
        _     <- c.queryRaw(s"SELECT somethingwrong").passWhenErrorType[SparqlClientError]
      } yield ()
    }

    "patch a named graph removing matching predicates" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props       <- cf.properties()
        _           <- c.createNamespace(props)
        graph       <- cf.load(f.id, f.label, f.value)
        _           <- c.replace(f.graph, graph)
        json        <- IO.fromEither(
                         parse(
                           s"""
                              |{
                              |  "@context": {
                              |    "label": "http://www.w3.org/2000/01/rdf-schema#label",
                              |    "schema": "http://schema.org/",
                              |    "nested": "http://localhost/nested/"
                              |  },
                              |  "@id": "http://localhost/${f.id}",
                              |  "label": "${f.label}-updated",
                              |  "nested": {
                              |     "schema:name": "name",
                              |     "schema:title": "title"
                              |  }
                              |}""".stripMargin
                         )
                       )
        strategy     = PatchStrategy.removePredicates(
                         Set(
                           "http://schema.org/value",
                           "http://www.w3.org/2000/01/rdf-schema#label"
                         )
                       )
        jsonAsGraph <- json.asExceptionalRdfGraph(IriNode(url"http://localhost/${f.id}"))
        _           <- c.patch(f.graph, jsonAsGraph, strategy)
        triples     <- c.triples()
        _            = triples should have size 4
        triples     <- c.triples(f.graph)
        _            = triples should have size 4
        predicates  <- c.triples().map(_.map(_._2))
        _            = predicates should contain theSameElementsAs Set(
                         "http://www.w3.org/2000/01/rdf-schema#label",
                         "http://schema.org/name",
                         "http://localhost/nested/",
                         "http://schema.org/title"
                       )
        objects     <- c.triples().map(_.map(_._3))
        _            = objects should contain allOf ("name", "title", s"${f.label}-updated")
      } yield ()
    }

    "patch a named graph retaining matching predicates" in { (cf: ClientFactory) =>
      val f = cf.fixture
      val c = cf.create(f.namespace)
      for {
        props       <- cf.properties()
        _           <- c.createNamespace(props)
        graph       <- cf.load(f.id, f.label, f.value)
        _           <- c.replace(f.graph, graph)
        json        <- IO.fromEither(
                         parse(
                           s"""
               |{
               |  "@context": {
               |    "label": "http://www.w3.org/2000/01/rdf-schema#label",
               |    "schema": "http://schema.org/",
               |    "nested": "http://localhost/nested/"
               |  },
               |  "@id": "http://localhost/${f.id}",
               |  "label": "${f.label}-updated",
               |  "nested": {
               |     "schema:name": "name",
               |     "schema:title": "title"
               |  }
               |}""".stripMargin
                         )
                       )
        strategy     = PatchStrategy.removeButPredicates(Set("http://schema.org/value"))
        jsonAsGraph <- json.asExceptionalRdfGraph(IriNode(url"http://localhost/${f.id}"))
        _           <- c.patch(f.graph, jsonAsGraph, strategy)
        triples     <- c.triples()
        _            = triples should have size 5
        triples     <- c.triples(f.graph)
        _            = triples should have size 5
        objects     <- c.triples().map(_.map(_._3))
        _            = objects should contain allOf (f.label + "-updated", f.value, "name", "title")
      } yield ()
    }
  }
}
