package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, ResourceF}
import fs2.Stream
import monix.bio.{IO, Task}

trait GlobalEventLog {

  /**
    * Get stream of all [[Event]]s
    * @param offset the offset to start from
    */
  def events(offset: Offset): Stream[Task, Envelope[Event]]

  /**
    * Get stream of all [[Event]]s for a project.
    *
    * @param project  the project reference
    * @param offset   the offset to start from
    */
  def events(project: ProjectRef, offset: Offset): IO[ProjectNotFound, Stream[Task, Envelope[Event]]]

  /**
    * Fetch the latest for the event as expanded JSON-LD.
    *
    * @param event  the event for which to fetch latest state
    */
  def latestStateAsExpandedJsonLd(event: Event): Task[Option[ResourceF[ExpandedJsonLd]]]

}
