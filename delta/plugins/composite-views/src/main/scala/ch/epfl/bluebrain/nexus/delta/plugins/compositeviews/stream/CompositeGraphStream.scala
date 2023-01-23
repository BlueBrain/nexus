package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemPipe, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{RemainingElems, Source}
import io.circe.Json
import monix.bio.UIO

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
    */
  def rebuild(source: CompositeViewSource, project: ProjectRef): Source

  /**
    * Get information about the remaining elements
    * @param source
    *   the composite view source
    * @param project
    *   the enclosing project
    */
  def remaining(source: CompositeViewSource, project: ProjectRef): Offset => UIO[Option[RemainingElems]]

}

object CompositeGraphStream {

  def apply(local: GraphResourceStream, remote: GraphResourceStream): CompositeGraphStream = new CompositeGraphStream {

    // For composite views, we don't need the source for indexing
    private val empty                                               = Json.obj()
    private def drainSource: ElemPipe[GraphResource, GraphResource] = _.map(_.map(_.copy(source = empty)))

    override def main(source: CompositeViewSource, project: ProjectRef): Source = {
      val stream = source match {
        case p: ProjectSource       => local.continuous(project, p.resourceTag.getOrElse(Tag.Latest), _)
        case c: CrossProjectSource  => local.continuous(c.project, c.resourceTag.getOrElse(Tag.Latest), _)
        case r: RemoteProjectSource => remote.continuous(r.project, r.resourceTag.getOrElse(Tag.Latest), _)
      }

      Source(stream(_).through(drainSource))
    }

    override def rebuild(source: CompositeViewSource, project: ProjectRef): Source = {
      val stream = source match {
        case p: ProjectSource       => local.currents(project, p.resourceTag.getOrElse(Tag.Latest), _)
        case c: CrossProjectSource  => local.currents(c.project, c.resourceTag.getOrElse(Tag.Latest), _)
        case r: RemoteProjectSource => remote.currents(r.project, r.resourceTag.getOrElse(Tag.Latest), _)
      }
      Source(stream(_).through(drainSource))
    }

    override def remaining(source: CompositeViewSource, project: ProjectRef): Offset => UIO[Option[RemainingElems]] =
      source match {
        case p: ProjectSource       => local.remaining(project, p.resourceTag.getOrElse(Tag.Latest), _)
        case c: CrossProjectSource  => local.remaining(c.project, c.resourceTag.getOrElse(Tag.Latest), _)
        case r: RemoteProjectSource => remote.remaining(r.project, r.resourceTag.getOrElse(Tag.Latest), _)
      }
  }

}
