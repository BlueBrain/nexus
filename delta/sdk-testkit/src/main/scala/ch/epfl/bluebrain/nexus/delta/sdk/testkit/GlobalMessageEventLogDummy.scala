package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.sourcing.projections.Message
import fs2.{Chunk, Stream}
import monix.bio.{IO, Task, UIO}

/**
  * A dummy GlobalEventLog for [[Message]] based logs.
  *
  * @param messages       the underlying messaged
  * @param projectFilter  the filter to filter messages for a project
  * @param orgFilter      the filter to filter messages for an organization
  * @param tagFilter      the filter to filter messages by tag
  */
class GlobalMessageEventLogDummy[T](
    messages: Seq[Message[T]],
    projectFilter: (ProjectRef, Message[T]) => Boolean,
    orgFilter: (Label, Message[T]) => Boolean,
    tagFilter: (TagLabel, Message[T]) => Boolean
) extends GlobalEventLog[Message[T]] {

  override def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[T]]] = {
    val tagMessages = messages.filter(msg => tag.forall(tagFilter(_, msg)))
    DummyHelpers.streamFromMessages(UIO.delay(tagMessages), offset, tagMessages.size.toLong).chunkLimit(1)
  }

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[ProjectRejection.ProjectNotFound, Stream[Task, Chunk[Message[T]]]] = {
    val projectMessages = messages
      .filter(projectFilter(project, _))
      .filter(msg => tag.forall(tagFilter(_, msg)))
    IO.evalTotal(
      DummyHelpers.streamFromMessages(UIO.delay(projectMessages), offset, projectMessages.size.toLong).chunkLimit(1)
    )
  }

  override def stream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[OrganizationRejection.OrganizationNotFound, Stream[Task, Chunk[Message[T]]]] = {
    val orgMessages = messages
      .filter(orgFilter(org, _))
      .filter(msg => tag.forall(tagFilter(_, msg)))
    IO.evalTotal(DummyHelpers.streamFromMessages(UIO.delay(orgMessages), offset, orgMessages.size.toLong).chunkLimit(1))
  }
}
