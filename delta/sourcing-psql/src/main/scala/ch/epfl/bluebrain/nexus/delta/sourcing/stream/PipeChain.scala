package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd

/**
  * An identified collection of pipe references along with their configuration. It can be compiled into a single
  * [[Operation]] that sets the `id` in the context of all elements processed by chaining all constituents together in the
  * provided order.
  *
  * @param pipes
  *   the collection of pipe references and their configuration
  */
final case class PipeChain(
    pipes: NonEmptyChain[(PipeRef, ExpandedJsonLd)]
)

object PipeChain {

  /**
    * Attempts to compile the chain into a single [[Operation]]. Reasons for failing are inability to lookup references in
    * the registry, mismatching configuration or mismatching In and Out types.
    * @param registry
    *   the registry to use for looking up source and pipe references
    */
  def compile(pipeChain: PipeChain, registry: ReferenceRegistry): Either[ProjectionErr, Operation] =
    for {
      configured <- pipeChain.pipes.traverse { case (ref, cfg) =>
        registry.lookup(ref).flatMap(_.withJsonLdConfig(cfg))
      }
      chained    <- configured.tail.foldLeftM[Either[ProjectionErr, *], Operation](configured.head) { case (acc, e) =>
        acc.andThen(e)
      }
    } yield chained

  def validate(pipeChain: PipeChain, registry: ReferenceRegistry): Either[ProjectionErr, Unit] =
    compile(pipeChain, registry).void

}
