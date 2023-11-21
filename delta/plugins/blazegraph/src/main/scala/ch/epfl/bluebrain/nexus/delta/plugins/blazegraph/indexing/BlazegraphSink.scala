package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphSink.{logger, BlazegraphBulk}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * Sink that pushed N-Triples into a given namespace in Blazegraph
  * @param client
  *   the Blazegraph client
  * @param chunkSize
  *   the maximum number of elements to be pushed in ES at once
  * @param maxWindow
  *   the maximum number of elements to be pushed at once
  * @param namespace
  *   the namespace
  */
final class BlazegraphSink(
    client: BlazegraphClient,
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration,
    namespace: String
)(implicit base: BaseUri)
    extends Sink {

  override type In = NTriples

  override def inType: Typeable[NTriples] = Typeable[NTriples]

  private val endpoint: Iri = base.endpoint.toIri

  implicit private val kamonComponent: KamonMetricComponent =
    KamonMetricComponent(BlazegraphViews.entityType.value)

  override def apply(elements: Chunk[Elem[NTriples]]): IO[Chunk[Elem[Unit]]] = {
    val bulk = elements.foldLeft(BlazegraphBulk.empty(endpoint)) {
      case (acc, Elem.SuccessElem(_, id, _, _, _, triples, _)) =>
        acc.replace(id, triples)
      case (acc, Elem.DroppedElem(_, id, _, _, _, _))          =>
        acc.drop(id)
      case (acc, _: Elem.FailedElem)                           =>
        acc
    }
    if (bulk.queries.nonEmpty)
      client
        .bulk(namespace, bulk.queries)
        .redeemWith(
          err =>
            logger
              .error(err)(s"Indexing in blazegraph namespace $namespace failed")
              .as(elements.map { _.failed(err) }),
          _ => IO.pure(markInvalidIdsAsFailed(elements, bulk.invalidIds))
        )
    else
      IO.pure(markInvalidIdsAsFailed(elements, bulk.invalidIds))
  }.span("blazegraphSink")

  private def markInvalidIdsAsFailed(elements: Chunk[Elem[NTriples]], invalidIds: Set[Iri]) =
    elements.map { e =>
      if (invalidIds.contains(e.id))
        e.failed(InvalidIri)
      else
        e.void
    }

}

object BlazegraphSink {

  private val logger = Logger[BlazegraphSink]

  def apply(client: BlazegraphClient, batchConfig: BatchConfig, namespace: String)(implicit base: BaseUri) =
    new BlazegraphSink(client, batchConfig.maxElements, batchConfig.maxInterval, namespace = namespace)

  final case class BlazegraphBulk(invalidIds: Set[Iri], queries: Vector[SparqlWriteQuery], endpoint: Iri) {

    private def parseUri(id: Iri) = id.resolvedAgainst(endpoint).toUri

    def replace(id: Iri, triples: NTriples): BlazegraphBulk =
      parseUri(id).fold(
        _ => copy(invalidIds = invalidIds + id),
        uri => copy(queries = queries :+ SparqlWriteQuery.replace(uri, triples))
      )

    def drop(id: Iri): BlazegraphBulk =
      parseUri(id).fold(
        _ => copy(invalidIds = invalidIds + id),
        uri => copy(queries = queries :+ SparqlWriteQuery.drop(uri))
      )

  }

  object BlazegraphBulk {
    def empty(endpoint: Iri): BlazegraphBulk = BlazegraphBulk(Set.empty, Vector.empty, endpoint)
  }
}
