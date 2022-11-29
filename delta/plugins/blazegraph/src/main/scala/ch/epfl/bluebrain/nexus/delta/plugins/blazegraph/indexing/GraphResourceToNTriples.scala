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

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[NTriples]] = {
    val graph = element.value.graph ++ element.value.metadataGraph
    Task
      .fromEither(graph.toNTriples)
      .redeem(
        err => element.failed(err),
        {
          case ntriples if ntriples.isEmpty => element.dropped
          case ntriples                     => element.success(ntriples)
        }
      )
  }
}
