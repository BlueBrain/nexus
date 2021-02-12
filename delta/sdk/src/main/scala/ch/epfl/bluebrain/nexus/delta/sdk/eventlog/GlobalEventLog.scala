package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.sourcing.projections.Message
import fs2.{Chunk, Stream}
import monix.bio.{IO, Task}

trait GlobalEventLog[T] {

  /**
    * Get stream of all events as ''T''.
    *
    * @param offset the offset to start from
    * @param tag    the optional tag used to filter the desired Ts
    */
  def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[T]]

  /**
    * Get stream of all events inside a project as ''T''.
    *
    * @param project the project reference
    * @param offset  the offset to start from
    * @param tag     the optional tag used to filter the desired Ts
    */
  def stream(project: ProjectRef, offset: Offset, tag: Option[TagLabel]): IO[ProjectNotFound, Stream[Task, Chunk[T]]]

  /**
    * Get stream of all events inside an organization as ''T''.
    *
    * @param org    the organization label
    * @param offset the offset to start from
    * @param tag    the optional tag used to filter the desired Ts
    */
  def stream(org: Label, offset: Offset, tag: Option[TagLabel]): IO[OrganizationNotFound, Stream[Task, Chunk[T]]]

}
