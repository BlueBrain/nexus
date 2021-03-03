package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects, ReferenceExchange}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, ProjectionId}
import fs2.{Chunk, Stream}
import monix.bio.{IO, Task}

import scala.concurrent.duration.FiniteDuration

/**
  * An implementation of [[GlobalEventLog]]
  */
final class BlazegraphGlobalEventLog private (
    eventLog: EventLog[Envelope[Event]],
    projects: Projects,
    orgs: Organizations,
    referenceExchanges: Set[ReferenceExchange],
    batchMaxSize: Int,
    batchMaxTimeout: FiniteDuration
)(implicit projectionId: ProjectionId, rcr: RemoteContextResolution)
    extends GlobalEventLog[Message[ResourceF[Graph]]] {

  override def stream(offset: Offset, tag: Option[TagLabel]): Stream[Task, Chunk[Message[ResourceF[Graph]]]] =
    exchange(eventLog.eventsByTag(Event.eventTag, offset), tag)

  override def stream(
      project: ProjectRef,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[ProjectNotFound, Stream[Task, Chunk[Message[ResourceF[Graph]]]]] =
    projects.fetch(project).as(exchange(eventLog.eventsByTag(Projects.projectTag(project), offset), tag))

  override def stream(
      org: Label,
      offset: Offset,
      tag: Option[TagLabel]
  ): IO[OrganizationNotFound, Stream[Task, Chunk[Message[ResourceF[Graph]]]]] =
    orgs.fetch(org).as(exchange(eventLog.eventsByTag(Organizations.orgTag(org), offset), tag))

  private def exchange(
      stream: Stream[Task, Envelope[Event]],
      tag: Option[TagLabel]
  ): Stream[Task, Chunk[Message[ResourceF[Graph]]]] =
    stream
      .map(_.toMessage)
      .groupWithin(batchMaxSize, batchMaxTimeout)
      .discardDuplicates()
      .evalMapFilterValue { event =>
        Task
          .tailRecM(referenceExchanges.toList) { // try all reference exchanges one at a time until there's a result
            case Nil              => Task.pure(Right(None))
            case exchange :: rest => exchange(event, tag).map(_.toRight(rest).map(value => Some(value)))
          }
          .flatMap {
            case Some(value) => value.encoder.graph(value.toResource.value).map(g => Some(value.toResource.map(_ => g)))
            case None        => Task.pure(None)
          }
      }
}

object BlazegraphGlobalEventLog {

  type EventStateResolution = Event => Task[ResourceF[ExpandedJsonLd]]

  /**
    * Create an instance of [[BlazegraphGlobalEventLog]].
    *
    * @param eventLog           the underlying [[EventLog]]
    * @param projects           the projects operations bundle
    * @param orgs               the organizations operations bundle
    * @param referenceExchanges the collection of [[ReferenceExchange]]s to fetch latest state
    * @param batchMaxSize       the maximum batching size. In this window, duplicated persistence ids are discarded
    * @param batchMaxTimeout    the maximum batching duration. In this window, duplicated persistence ids are discarded
    */
  def apply(
      eventLog: EventLog[Envelope[Event]],
      projects: Projects,
      orgs: Organizations,
      referenceExchanges: Set[ReferenceExchange],
      batchMaxSize: Int,
      batchMaxTimeout: FiniteDuration
  )(implicit projectionId: ProjectionId, rcr: RemoteContextResolution): BlazegraphGlobalEventLog =
    new BlazegraphGlobalEventLog(eventLog, projects, orgs, referenceExchanges, batchMaxSize, batchMaxTimeout)

}
