package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.QueryGraph
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import fs2.Chunk
import monix.bio.Task
import shapeless.Typeable

import scala.concurrent.duration.FiniteDuration

/**
  * A sink that queries N-Triples in Blazegraph, transforms them, and pushes the result to the provided sink
  * @param queryGraph
  *   how to query the blazegraph
  * @param transform
  *   function to transform a graph into the format needed by the sink
  * @param sink
  *   function that allows
  * @param chunkSize
  *   the maximum number of elements to be pushed in ES at once
  * @param maxWindow
  *   the maximum number of elements to be pushed at once
  * @tparam SinkFormat
  *   the type of data accepted by the sink
  */
final class CompositeSink[SinkFormat](
    queryGraph: QueryGraph,
    transform: GraphResource => Task[SinkFormat],
    sink: Chunk[Elem[SinkFormat]] => Task[Chunk[Elem[Unit]]],
    override val chunkSize: Int,
    override val maxWindow: FiniteDuration
) extends Sink {

  override type In = GraphResource
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]

  private def query: Elem[GraphResource] => Task[Elem[GraphResource]] =
    elem => elem.evalMapFilter(gr => queryGraph(gr))

  private def liftedTransform: Elem[GraphResource] => Task[Elem[SinkFormat]] =
    elem => elem.evalMap(transform)

  private def queryAndTransform: Elem[GraphResource] => Task[Elem[SinkFormat]] =
    elem => query(elem).flatMap(liftedTransform)

  override def apply(elements: Chunk[Elem[GraphResource]]): Task[Chunk[Elem[Unit]]] =
    elements
      .traverse(queryAndTransform)
      .flatMap(sink)

}
