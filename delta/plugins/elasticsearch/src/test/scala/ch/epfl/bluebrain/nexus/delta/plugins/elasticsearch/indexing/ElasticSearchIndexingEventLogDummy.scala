package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.{ElasticSearchIndexingEventLog, IndexingData}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Message
import fs2.{Chunk, Stream}
import monix.bio.Task

class ElasticSearchIndexingEventLogDummy(
    messages: Map[(ProjectRef, Option[TagLabel]), Seq[Message[ResourceF[IndexingData]]]]
) extends ElasticSearchIndexingEventLog {

  private def allMessages(project: ProjectRef): Iterable[Message[ResourceF[IndexingData]]] =
    messages.view.collect { case ((`project`, _), values) => values }.flatten

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[ResourceF[IndexingData]]]]                                 =
    tag match {
      case Some(_) if messages.contains(project -> tag) =>
        Stream.iterable(messages(project -> tag).map(Chunk(_))) ++ Stream.never[Task]
      case Some(_) => Stream.never[Task]
      case None    => Stream.iterable(allMessages(project).map(Chunk(_))) ++ Stream.never[Task]

    }

}
