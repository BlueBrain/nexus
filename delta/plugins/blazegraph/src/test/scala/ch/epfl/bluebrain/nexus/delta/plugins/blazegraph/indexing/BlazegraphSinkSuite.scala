package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import fs2.Chunk
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class BlazegraphSinkSuite extends BioSuite with BlazegraphClientSetup.Fixture with TestHelpers {

  override def munitFixtures: Seq[AnyFixture[_]] = List(blazegraphClient)

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private lazy val client = blazegraphClient()
  private val namespace   = "test_sink"
  private lazy val sink   = new BlazegraphSink(client, 2, 50.millis, namespace)

  private val resource1Id       = iri"https://bbp.epfl.ch/resource1"
  private val resource1Ntriples = NTriples(contentOf("sparql/resource1.ntriples"), resource1Id)

  private val resource2Id       = iri"https://bbp.epfl.ch/resource2"
  private val resource2Ntriples = NTriples(contentOf("sparql/resource2.ntriples"), resource2Id)

  private val resource3Id       = iri"https://bbp.epfl.ch/resource3"
  private val resource3Ntriples = NTriples(contentOf("sparql/resource3.ntriples"), resource3Id)

  private val entityType = EntityType("MyResource")

  private val constructQuery = SparqlConstructQuery.unsafe("CONSTRUCT {?s ?p ?o} WHERE { ?s ?p ?o }")

  private val allResources = Chunk(
    resource1Id -> resource1Ntriples,
    resource2Id -> resource2Ntriples,
    resource3Id -> resource3Ntriples
  )

  private val resource123Graph = allResources.foldLeft(Graph.empty) { case (acc, (_, ntriples)) =>
    acc ++ Graph(ntriples).getOrElse(Graph.empty)
  }

  private val resource13Graph = List(resource1Ntriples, resource3Ntriples).foldLeft(Graph.empty) {
    case (acc, ntriples) => acc ++ Graph(ntriples).getOrElse(Graph.empty)
  }

  test("Create the namespace") {
    client.createNamespace(namespace)
  }

  test("Push a chunk of elements and retrieve them") {
    val chunk = allResources.zipWithIndex.map { case ((id, ntriples), index) =>
      SuccessElem(entityType, id, None, Instant.EPOCH, Offset.at(index.toLong + 1), ntriples, 1)
    }

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.void))
      _ <- client
             .query(Set(namespace), constructQuery, SparqlQueryResponseType.SparqlNTriples)
             .map { response => Graph(response.value).toOption }
             .assertSome(resource123Graph)
    } yield ()
  }

  test("Delete dropped elements from the namespace") {
    val chunk = Chunk(
      DroppedElem(entityType, resource2Id, None, Instant.EPOCH, Offset.at(4L), 1)
    )

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.void))
      _ <- client
             .query(Set(namespace), constructQuery, SparqlQueryResponseType.SparqlNTriples)
             .map { response => Graph(response.value).toOption }
             .assertSome(resource13Graph)
    } yield ()

  }

  test("Report errors when the id is not a valid absolute iri") {
    val chunk = Chunk(
      SuccessElem(entityType, nxv + "é-wrong", None, Instant.EPOCH, Offset.at(5L), resource1Ntriples, 1)
    )

    for {
      _ <- sink.apply(chunk).assert(chunk.map(_.failed(InvalidIri)))
      _ <- client
             .query(Set(namespace), constructQuery, SparqlQueryResponseType.SparqlNTriples)
             .map { response => Graph(response.value).toOption }
             .assertSome(resource13Graph)
    } yield ()
  }

}
