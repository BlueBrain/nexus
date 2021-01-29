package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import fs2.Stream
import monix.bio.{IO, Task}

trait GlobalEventLog[T] {

  /**
    * Get stream of all [[Event]]s
    * @param offset the offset to start from
    */
  def events(offset: Offset): Stream[Task, T]

  /**
    * Get stream of all [[Event]]s for a project.
    *
    * @param project  the project reference
    * @param offset   the offset to start from
    */
  def events(project: ProjectRef, offset: Offset): IO[ProjectNotFound, Stream[Task, T]]

  /**
    * Get stream of all [[Event]]s for an organization
    *
    * @param org      the organization label
    * @param offset   the offset to start from
    */
  def events(org: Label, offset: Offset): IO[OrganizationNotFound, Stream[Task, T]]

}
