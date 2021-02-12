package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.sourcing.projections.Message
import fs2.Stream
import monix.bio.{IO, Task}

trait GlobalEventLog[T] {

  /**
    * Get stream of all [[Event]]s
    * @param offset the offset to start from
    * @param tag    optional used to filter events.
    */
  def stream(offset: Offset, tag: Option[TagLabel] = None): Stream[Task, Message[T]]

  /**
    * Get stream of all [[Event]]s for a project.
    *
    * @param project  the project reference
    * @param offset   the offset to start from
    * @param tag      optional used to filter events.
    */
  def projectStream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel] = None
  ): IO[ProjectNotFound, Stream[Task, Message[T]]]

  /**
    * Get stream of all [[Event]]s for an organization
    *
    * @param org      the organization label
    * @param offset   the offset to start from
    * @param tag      optional used to filter events.
    */
  def orgStream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel] = None
  ): IO[OrganizationNotFound, Stream[Task, Message[T]]]

}
