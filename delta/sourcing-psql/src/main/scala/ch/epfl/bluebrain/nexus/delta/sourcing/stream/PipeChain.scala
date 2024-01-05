package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DiscardMetadata, FilterBySchema, FilterByType, FilterDeprecated}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ViewRestriction

/**
  * An identified collection of pipe references along with their configuration. It can be compiled into a single
  * [[Operation]] that sets the `id` in the context of all elements processed by chaining all constituents together in
  * the provided order.
  *
  * @param pipes
  *   the collection of pipe references and their configuration
  */
final case class PipeChain(
    pipes: NonEmptyChain[(PipeRef, ExpandedJsonLd)]
)

object PipeChain {

  type Compile = PipeChain => Either[ProjectionErr, Operation]

  def apply(first: (PipeRef, ExpandedJsonLd), others: (PipeRef, ExpandedJsonLd)*): PipeChain =
    new PipeChain(NonEmptyChain(first, others: _*))

  /**
    * Attempts to compile the chain into a single [[Operation]]. Reasons for failing are inability to lookup references
    * in the registry, mismatching configuration or mismatching In and Out types.
    * @param registry
    *   the registry to use for looking up source and pipe references
    */
  def compile(pipeChain: PipeChain, registry: ReferenceRegistry): Either[ProjectionErr, Operation] =
    for {
      configured <- pipeChain.pipes.traverse { case (ref, cfg) =>
                      registry.lookup(ref).flatMap(_.withJsonLdConfig(cfg))
                    }
      chained    <- Operation.merge(configured)
    } yield chained

  def validate(pipeChain: PipeChain, registry: ReferenceRegistry): Either[ProjectionErr, Unit] =
    compile(pipeChain, registry).void

  /**
    * Create a [[PipeChain]] from the given constraints
    * @param resourceSchemas
    *   filter on schemas if non empty
    * @param resourceTypes
    *   filter on resource types if non empty
    * @param includeMetadata
    *   include resource metadata if true
    * @param includeDeprecated
    *   include deprecated resources if true
    */
  def apply(
      resourceSchemas: ViewRestriction,
      resourceTypes: ViewRestriction,
      includeMetadata: Boolean,
      includeDeprecated: Boolean
  ): Option[PipeChain] = {
    val resourceSchemasPipeChain = resourceSchemas.asRestrictedTo.map(FilterBySchema(_)).toList
    val resourceTypesPipeChain   = resourceTypes.asRestrictedTo.map(FilterByType(_)).toList

    NonEmptyChain
      .fromSeq {
        resourceSchemasPipeChain ++ resourceTypesPipeChain ++
          List(
            !includeDeprecated -> FilterDeprecated(),
            !includeMetadata   -> DiscardMetadata()
          ).mapFilter { case (b, p) => Option.when(b)(p) }
      }
      .map(PipeChain(_))
  }

}
