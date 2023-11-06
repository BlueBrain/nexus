package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.RemoteSourceClientConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.MetadataPredicates
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.RemoteGraphStream.fromNQuads
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.MissingPredicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NQuads}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems, Source}
import fs2.Stream
import io.circe.Json

final class RemoteGraphStream(
    deltaClient: DeltaClient,
    config: RemoteSourceClientConfig,
    metadataPredicates: MetadataPredicates
)(implicit timer: Timer[IO], cs: ContextShift[IO]) {

  /**
    * Get a continuous stream of element as a [[Source]] for the main branch
    * @param remote
    *   the remote source
    */
  def main(remote: RemoteProjectSource): Source =
    Source(stream(remote, CompositeBranch.Run.Main))

  /**
    * Get the current elements as a [[Source]] for the rebuild branch
    * @param remote
    *   the remote source
    */
  def rebuild(remote: RemoteProjectSource): Source =
    Source(stream(remote, CompositeBranch.Run.Rebuild))

  private def stream(remote: RemoteProjectSource, run: CompositeBranch.Run): Offset => ElemStream[GraphResource] =
    deltaClient
      .elems(remote, run, _)
      .groupWithin(config.maxBatchSize, config.maxTimeWindow)
      .evalMap { chunk =>
        chunk.traverse { elem =>
          populateElem(remote, elem)
        }
      }
      .flatMap(Stream.chunk)

  private def populateElem(remote: RemoteProjectSource, elem: Elem[Unit]): IO[Elem[GraphResource]] =
    elem.evalMapFilter { _ =>
      deltaClient.resourceAsNQuads(remote, elem.id).toCatsIO.flatMap {
        _.traverse { nquads => fromNQuads(elem, remote.project, nquads, metadataPredicates) }
      }
    }

  /**
    * Get information about the remaining elements
    * @param source
    *   the composite view source
    */
  def remaining(source: RemoteProjectSource, offset: Offset): IO[RemainingElems] =
    deltaClient.remaining(source, offset).toCatsIO

}

object RemoteGraphStream {

  /**
    * Injects the elem value from the n-quads
    */
  def fromNQuads(
      elem: Elem[Unit],
      project: ProjectRef,
      nQuads: NQuads,
      metadataPredicates: MetadataPredicates
  ): IO[GraphResource] = IO.fromEither {
    for {
      graph      <- Graph(nQuads)
      valueGraph  = graph.filter { case (_, p, _) => !metadataPredicates.values.contains(p) }
      metaGraph   = graph.filter { case (_, p, _) => metadataPredicates.values.contains(p) }
      types       = graph.rootTypes
      schema     <- metaGraph
                      .find(elem.id, nxv.constrainedBy.iri)
                      .map(triple => ResourceRef(iri"${triple.getURI}"))
                      .toRight(MissingPredicate(nxv.constrainedBy.iri))
      deprecated <- metaGraph
                      .find(elem.id, nxv.deprecated.iri)
                      .map(_.getLiteralLexicalForm.toBoolean)
                      .toRight(MissingPredicate(nxv.deprecated.iri))

    } yield GraphResource(
      elem.tpe,
      project,
      elem.id,
      elem.rev,
      deprecated,
      schema,
      types,
      valueGraph,
      metaGraph,
      Json.obj()
    )
  }
}
