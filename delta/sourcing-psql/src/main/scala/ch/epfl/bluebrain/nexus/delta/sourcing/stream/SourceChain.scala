package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.Chain
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import cats.implicits._

/**
  * A reference to a source, its id and configuration along with a collection of pipe reference and their configuration.
  * It can be compiled into a single runnable source by looking up in the [[ReferenceRegistry]] all its constituents and
  * chaining them together.
  *
  * @param source
  *   a reference to a [[SourceDef]]
  * @param sourceId
  *   the id of the resulting [[Source]]
  * @param sourceConfig
  *   the required configuration for constructing a [[Source]]
  * @param pipes
  *   a collection of pipe references and their configuration
  */
final case class SourceChain(
    source: SourceRef,
    sourceId: Iri,
    sourceConfig: ExpandedJsonLd,
    pipes: Chain[(PipeRef, ExpandedJsonLd)]
) {

  /**
    * Attempts to compile the chain into a single [[Source]]. Reasons for failing are inability to lookup references in
    * the registry, mismatching configuration or mismatching In and Out types.
    * @param registry
    *   the registry to use for looking up source and pipe references
    */
  def compile(registry: ReferenceRegistry): Either[ProjectionErr, Source] = {
    for {
      sourceDef        <- registry.lookup(source)
      configuredSource <- sourceDef.withJsonLdConfig(sourceConfig, sourceId)
      configuredPipes  <- pipes.traverse { case (ref, cfg) =>
                            registry.lookup(ref).flatMap(_.withJsonLdConfig(cfg))
                          }
      chained          <- configuredPipes.foldLeftM[Either[ProjectionErr, *], Source](configuredSource) { (acc, e) =>
                            acc.through(e)
                          }
    } yield chained
  }
}
