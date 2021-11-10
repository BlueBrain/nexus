package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Message
import fs2.{Chunk, Stream}
import monix.bio.Task

class IndexingSourceDummy(
    messages: Map[ProjectRef, Seq[Message[EventExchangeValue[_, _]]]]
) extends IndexingSource {

  override def apply(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[EventExchangeValue[_, _]]]] =
    Stream.iterable(messages(project)).map(Chunk(_)) ++ Stream.never[Task]

}
