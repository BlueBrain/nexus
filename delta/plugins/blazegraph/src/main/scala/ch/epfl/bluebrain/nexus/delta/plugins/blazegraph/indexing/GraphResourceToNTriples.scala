package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeRef}
import monix.bio.Task
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

  def graphToNTriples(graphResource: GraphResource): Task[NTriples] = {
    val graph = graphResource.graph ++ graphResource.metadataGraph
    Task.fromEither(graph.toNTriples)
  }

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[NTriples]] = {
    element
      .evalMap(graphToNTriples)
      .map {
        case ntriples: SuccessElem[NTriples] if ntriples.value.isEmpty => element.dropped
        case ntriples                                                  => ntriples
      }
  }
}
