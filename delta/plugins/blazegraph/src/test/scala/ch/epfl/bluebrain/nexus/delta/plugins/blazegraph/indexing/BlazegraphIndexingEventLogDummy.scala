package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Message
import fs2.{Chunk, Stream}
import monix.bio.Task

class BlazegraphIndexingEventLogDummy(
    messages: Map[(ProjectRef, Option[TagLabel]), Seq[Message[ResourceF[Graph]]]]
) extends BlazegraphIndexingEventLog {

  private def allMessages(project: ProjectRef): Iterable[Message[ResourceF[Graph]]] =
    messages.view.collect { case ((`project`, _), values) => values }.flatten

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[ResourceF[Graph]]]]                                 =
    tag match {
      case Some(_) if messages.contains(project -> tag) => Stream.iterable(messages(project -> tag).map(Chunk(_)))
      case Some(_) => Stream.empty
      case None    => Stream.iterable(allMessages(project).map(Chunk(_)))
    }

}
