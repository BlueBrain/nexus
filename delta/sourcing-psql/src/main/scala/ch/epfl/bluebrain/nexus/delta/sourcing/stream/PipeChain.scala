package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.PipeChainOutNotUnitErr

/**
  * An identified collection of pipe references along with their configuration. It can be compiled into a single
  * [[Pipe]] that sets the `id` in the context of all elements processed by chaining all constituents together in the
  * provided order.
  *
  * @param id
  *   the chain identifier
  * @param pipes
  *   the collection of pipe references and their configuration
  */
final case class PipeChain(
    id: Iri,
    pipes: NonEmptyChain[(PipeRef, ExpandedJsonLd)]
) {

  /**
    * Attempts to compile the chain into a single [[Pipe]]. Reasons for failing are inability to lookup references in
    * the registry, mismatching configuration or mismatching In and Out types.
    * @param registry
    *   the registry to use for looking up source and pipe references
    */
  def compile(registry: ReferenceRegistry): Either[ProjectionErr, Pipe.Aux[_, Unit]] =
    for {
      configured <- pipes.traverse { case (ref, cfg) =>
                      registry.lookup(ref).flatMap(_.withJsonLdConfig(cfg))
                    }
      chained    <- configured.tail.foldLeftM[Either[ProjectionErr, *], Pipe](configured.head) { case (acc, e) =>
                      acc.andThen(e)
                    }
      identified  = chained.prependPipeChainId(id)
      asUnit     <- if (identified.outType.cast(()).isEmpty) Left(PipeChainOutNotUnitErr(identified))
                    else Right(identified.asInstanceOf[Pipe.Aux[_, Unit]])
    } yield asUnit
}
