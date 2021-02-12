package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.sourcing.projections.Message
import fs2.Stream
import monix.bio.{IO, Task, UIO}

class GlobalEventLogDummy[T](
    messages: Seq[Message[T]],
    projectFilter: (ProjectRef, Message[T]) => Boolean,
    orgFilter: (Label, Message[T]) => Boolean,
    tagFilter: (TagLabel, Message[T]) => Boolean
) extends GlobalEventLog[T] {

  /**
    * Get stream of all [[Event]]s
    *
    * @param offset the offset to start from
    * @param tag    optional used to filter events.
    */
  override def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Message[T]] = {
    val tagMessages = messages.filter(msg => tag.forall(tagFilter(_, msg)))
    DummyHelpers.streamFromMessages(UIO.delay(tagMessages), offset, tagMessages.size.toLong)
  }

  /**
    * Get stream of all [[Event]]s for a project.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    * @param tag     optional used to filter events.
    */
  override def projectStream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[ProjectRejection.ProjectNotFound, Stream[Task, Message[T]]] = {
    val projectMessages = messages
      .filter(projectFilter(project, _))
      .filter(msg => tag.forall(tagFilter(_, msg)))
    IO.evalTotal(DummyHelpers.streamFromMessages(UIO.delay(projectMessages), offset, projectMessages.size.toLong))
  }

  /**
    * Get stream of all [[Event]]s for an organization
    *
    * @param org    the organization label
    * @param offset the offset to start from
    * @param tag    optional used to filter events.
    */
  override def orgStream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[OrganizationRejection.OrganizationNotFound, Stream[Task, Message[T]]] = {
    val orgMessages = messages
      .filter(orgFilter(org, _))
      .filter(msg => tag.forall(tagFilter(_, msg)))
    IO.evalTotal(DummyHelpers.streamFromMessages(UIO.delay(orgMessages), offset, orgMessages.size.toLong))
  }
}
