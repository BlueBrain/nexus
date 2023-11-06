package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeRef}
import shapeless.Typeable

/**
  * Pipe that transforms a [[GraphResource]] into NTriples
  */
object GraphResourceToNTriples extends Pipe {

  override type In  = GraphResource
  override type Out = NTriples
  override def ref: PipeRef                    = PipeRef.unsafe("graph-resource-to-ntriples")
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]
  override def outType: Typeable[NTriples]     = Typeable[NTriples]

  def graphToNTriples(graphResource: GraphResource): IO[Option[NTriples]] = {
    val graph = graphResource.graph ++ graphResource.metadataGraph
    IO
      .fromEither(graph.toNTriples)
      .map(triples => Option.when(!triples.isEmpty)(triples))
  }

  override def apply(element: SuccessElem[GraphResource]): IO[Elem[NTriples]] =
    element.evalMapFilter(graphToNTriples)
}
