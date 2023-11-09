package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphClientSetup
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import fs2.Chunk
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class BlazegraphSinkSuite extends CatsEffectSuite with BlazegraphClientSetup.Fixture with TestHelpers {

  override def munitFixtures: Seq[AnyFixture[_]] = List(blazegraphClient)

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private lazy val client = blazegraphClient()
  private val namespace   = "test_sink"

  def createSink(namespace: String) = new BlazegraphSink(client, 2, 50.millis, namespace)

  private lazy val sink = createSink(namespace)

  private val resource1Id              = iri"https://bbp.epfl.ch/resource1"
  private val resource1Ntriples        = NTriples(contentOf("sparql/resource1.ntriples"), resource1Id)
  private val resource1NtriplesUpdated = NTriples(contentOf("sparql/resource1_updated.ntriples"), resource1Id)

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

  private def asElems(chunk: Chunk[(Iri, NTriples)]) =
    chunk.zipWithIndex.map { case ((id, ntriples), index) =>
      SuccessElem(entityType, id, None, Instant.EPOCH, Offset.at(index.toLong + 1), ntriples, 1)
    }

  private def createGraph(chunk: Chunk[(Iri, NTriples)]) = chunk.foldLeft(Graph.empty) { case (acc, (_, ntriples)) =>
    acc ++ Graph(ntriples).getOrElse(Graph.empty)
  }

  private def dropped(id: Iri, offset: Offset) = DroppedElem(entityType, id, None, Instant.EPOCH, offset, 1)

  private def query(namespace: String) =
    client
      .query(Set(namespace), constructQuery, SparqlQueryResponseType.SparqlNTriples)
      .map { response => Graph(response.value).toOption }

  test("Create the namespace") {
    client.createNamespace(namespace)
  }

  test("Push a chunk of elements and retrieve them") {
    val input    = asElems(allResources)
    val expected = createGraph(allResources)

    for {
      _ <- sink.apply(asElems(allResources)).assertEquals(input.map(_.void))
      _ <- query(namespace).assertSome(expected)
    } yield ()
  }

  test("Delete dropped elements from the namespace") {
    val input = Chunk(dropped(resource2Id, Offset.at(4L)))

    val expected = createGraph(Chunk(resource1Id -> resource1Ntriples, resource3Id -> resource3Ntriples))

    for {
      _ <- sink.apply(input).assertEquals(input.map(_.void))
      _ <- query(namespace).assertSome(expected)
    } yield ()

  }

  test("Report errors when the id is not a valid absolute iri") {
    val chunk    = Chunk(
      SuccessElem(entityType, nxv + "é-wrong", None, Instant.EPOCH, Offset.at(5L), resource1Ntriples, 1)
    )
    val expected = createGraph(Chunk(resource1Id -> resource1Ntriples, resource3Id -> resource3Ntriples))

    for {
      _ <- sink.apply(chunk).assertEquals(chunk.map(_.failed(InvalidIri)))
      _ <- query(namespace).assertSome(expected)
    } yield ()
  }

  test("When the same resource appears twice in a chunk, only the last update prevails") {
    val namespace = "test_last_update"
    val sink      = createSink(namespace)

    val input = Chunk(
      resource1Id -> resource1Ntriples,
      resource2Id -> resource2Ntriples,
      resource1Id -> resource1NtriplesUpdated
    )

    val expected = createGraph(Chunk(resource2Id -> resource2Ntriples, resource1Id -> resource1NtriplesUpdated))

    for {
      _ <- client.createNamespace(namespace).assertEquals(true)
      _ <- sink.apply(asElems(input))
      _ <- query(namespace).assertSome(expected)
    } yield ()
  }

  test("When the same resource appears twice in a chunk, only the last delete prevails") {
    val namespace = "test_last_delete"
    val sink      = createSink(namespace)

    val indexingChunk = asElems(
      Chunk(
        resource1Id -> resource1Ntriples,
        resource2Id -> resource2Ntriples
      )
    )

    val deleteChunk = Chunk.singleton(dropped(resource1Id, Offset.at(3L)))
    val chunk       = Chunk.concat(List(indexingChunk, deleteChunk))

    val expected = createGraph(Chunk.singleton(resource2Id -> resource2Ntriples))

    for {
      _ <- client.createNamespace(namespace).assertEquals(true)
      _ <- sink.apply(chunk)
      _ <- query(namespace).assertSome(expected)
    } yield ()
  }

}
