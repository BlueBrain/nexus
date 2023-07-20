package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.QueryGraph
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
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

  private def queryTransform: GraphResource => Task[Option[SinkFormat]] = gr =>
    for {
      graph       <- queryGraph(gr)
      transformed <- graph.traverse(transform)
    } yield transformed

  override def apply(elements: Chunk[Elem[GraphResource]]): Task[Chunk[Elem[Unit]]] =
    elements
      .traverse {
        case e: SuccessElem[GraphResource] => e.evalMapFilter(queryTransform)
        case e: DroppedElem                => Task.pure(e)
        case e: FailedElem                 => Task.pure(e)
      }
      .flatMap(sink)

}
