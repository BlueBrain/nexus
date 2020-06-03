package ch.epfl.bluebrain.nexus.admin.routes

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.server.Rejections._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, PathMatcher0, Route}
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query._
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.admin.config.AppConfig
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.PersistenceConfig
import ch.epfl.bluebrain.nexus.admin.config.Permissions._
import ch.epfl.bluebrain.nexus.admin.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent.JsonLd._
import ch.epfl.bluebrain.nexus.admin.persistence.TaggingAdapter._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent.JsonLd._
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Permission
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import io.circe.syntax._
import io.circe.{Encoder, Printer}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * Server Sent Events routes for organizations, projects and the entire event log.
  */
class EventRoutes(
    implicit as: ActorSystem,
    pc: PersistenceConfig,
    icc: IamClientConfig,
    ic: IamClient[Task]
) extends AuthDirectives(ic) {

  private val pq: EventsByTagQuery = PersistenceQuery(as).readJournalFor[EventsByTagQuery](pc.queryJournalPlugin)
  private val printer: Printer     = Printer.noSpaces.copy(dropNullValues = true)

  def routes: Route =
    concat(
      routesFor("orgs" / "events", OrganizationTag, orgs.read, typedEventToSse[OrganizationEvent]),
      routesFor("projects" / "events", ProjectTag, projects.read, typedEventToSse[ProjectEvent]),
      routesFor("events", EventTag, events.read, eventToSse)
    )

  private def routesFor(
      pm: PathMatcher0,
      tag: String,
      permission: Permission,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Route =
    (get & pathPrefix(pm) & pathEndOrSingleSlash) {
      extractToken { implicit token =>
        authorizeOn(Path./, permission).apply {
          lastEventId { offset =>
            complete(source(tag, offset, toSse))
          }
        }
      }
    }

  protected def source(
      tag: String,
      offset: Offset,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Source[ServerSentEvent, NotUsed] = {
    pq.eventsByTag(tag, offset)
      .flatMapConcat(ee => Source(toSse(ee).toList))
      .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)
  }

  private def lastEventId: Directive1[Offset] =
    optionalHeaderValueByName(`Last-Event-ID`.name)
      .map(_.map(id => `Last-Event-ID`(id)))
      .flatMap {
        case Some(header) =>
          Try[Offset](TimeBasedUUID(UUID.fromString(header.id))) orElse Try(Sequence(header.id.toLong)) match {
            case Success(value) => provide(value)
            case Failure(_)     => reject(validationRejection("The value of the `Last-Event-ID` header is not valid."))
          }
        case None => provide(NoOffset)
      }

  private def aToSse[A: Encoder](a: A, offset: Offset): ServerSentEvent = {
    val json = a.asJson.sortKeys(AppConfig.orderedKeys)
    ServerSentEvent(
      data = json.printWith(printer),
      eventType = json.hcursor.get[String]("@type").toOption,
      id = offset match {
        case NoOffset            => None
        case Sequence(value)     => Some(value.toString)
        case TimeBasedUUID(uuid) => Some(uuid.toString)
      }
    )
  }

  private def eventToSse(envelope: EventEnvelope): Option[ServerSentEvent] =
    envelope.event match {
      case value: OrganizationEvent => Some(aToSse(value, envelope.offset))
      case value: ProjectEvent      => Some(aToSse(value, envelope.offset))
      case _                        => None
    }

  private def typedEventToSse[A: Encoder](envelope: EventEnvelope)(implicit A: ClassTag[A]): Option[ServerSentEvent] =
    envelope.event match {
      case A(a) => Some(aToSse(a, envelope.offset))
      case _    => None
    }
}

object EventRoutes {
  def apply()(implicit as: ActorSystem, pc: PersistenceConfig, icc: IamClientConfig, ic: IamClient[Task]): EventRoutes =
    new EventRoutes
}
