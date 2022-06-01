package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Message
import fs2.{Chunk, Stream}
import monix.bio.Task

class IndexingSourceDummy(
    messages: Map[(ProjectRef, Option[UserTag]), Seq[Message[EventExchangeResult]]]
) extends IndexingSource {

  override def apply(
      project: ProjectRef,
      offset: Offset,
      tag: Option[UserTag]
  ): Stream[Task, Chunk[Message[EventExchangeResult]]] =
    tag match {
      case Some(_) if messages.contains(project -> tag) =>
        Stream.iterable(messages(project -> tag).map(Chunk(_))) ++ Stream.never[Task]
      case Some(_) => Stream.never[Task]
      case None    => Stream.iterable(allMessages(project).map(Chunk(_))) ++ Stream.never[Task]

    }

  private def allMessages(project: ProjectRef) =
    messages.view.collect { case ((`project`, _), values) => values }.flatten

}
