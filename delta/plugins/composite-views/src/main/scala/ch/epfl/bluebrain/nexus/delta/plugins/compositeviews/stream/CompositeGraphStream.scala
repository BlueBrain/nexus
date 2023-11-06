package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemPipe, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{RemainingElems, Source}
import io.circe.Json

/**
  * Allows to compute the stream operations from a [[CompositeViewSource]]
  */
trait CompositeGraphStream {

  /**
    * Get a continuous stream of element as a [[Source]] for the main branch
    * @param source
    *   the composite view source
    * @param project
    *   the enclosing project
    */
  def main(source: CompositeViewSource, project: ProjectRef): Source

  /**
    * Get the current elements as a [[Source]] for the rebuild branch
    * @param source
    *   the composite view source
    * @param project
    *   the enclosing project
    * @param projectionTypes
    *   the projection resource types to use to filter the stream
    */
  def rebuild(source: CompositeViewSource, project: ProjectRef, projectionTypes: Set[Iri]): Source

  /**
    * Get information about the remaining elements
    * @param source
    *   the composite view source
    * @param project
    *   the enclosing project
    */
  def remaining(source: CompositeViewSource, project: ProjectRef): Offset => IO[Option[RemainingElems]]

}

object CompositeGraphStream {

  def apply(local: GraphResourceStream, remote: RemoteGraphStream): CompositeGraphStream = new CompositeGraphStream {

    // For composite views, we don't need the source for indexing
    private val empty                                               = Json.obj()
    private def drainSource: ElemPipe[GraphResource, GraphResource] = _.map(_.map(_.copy(source = empty)))

    override def main(source: CompositeViewSource, project: ProjectRef): Source = {
      source match {
        case p: ProjectSource       =>
          Source(local.continuous(project, p.selectFilter, _).through(drainSource))
        case c: CrossProjectSource  =>
          Source(local.continuous(c.project, c.selectFilter, _).through(drainSource))
        case r: RemoteProjectSource => remote.main(r)
      }
    }

    override def rebuild(
        source: CompositeViewSource,
        project: ProjectRef,
        projectionTypes: Set[Iri]
    ): Source = {
      source match {
        case p: ProjectSource       =>
          val filter = p.selectFilter.copy(types = p.selectFilter.types ++ projectionTypes)
          Source(local.currents(project, filter, _).through(drainSource))
        case c: CrossProjectSource  =>
          val filter = c.selectFilter.copy(types = c.selectFilter.types ++ projectionTypes)
          Source(local.currents(c.project, filter, _).through(drainSource))
        case r: RemoteProjectSource =>
          remote.rebuild(r)
      }
    }

    override def remaining(source: CompositeViewSource, project: ProjectRef): Offset => IO[Option[RemainingElems]] =
      source match {
        case p: ProjectSource       => local.remaining(project, p.selectFilter, _)
        case c: CrossProjectSource  => local.remaining(c.project, c.selectFilter, _)
        case r: RemoteProjectSource => remote.remaining(r, _).map(Some(_))
      }
  }

}
