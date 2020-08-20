package ch.epfl.bluebrain.nexus.kg.routes

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query._
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationResource
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import io.circe.syntax._
import io.circe.{Encoder, Printer}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class EventRoutes(acls: Acls[Task], realms: Realms[Task], caller: Caller)(implicit
    as: ActorSystem,
    config: AppConfig
) extends AuthDirectives(acls, realms)(config.http, global) {

  private val read: Permission       = Permission.unsafe("resources/read")
  private val printer: Printer       = Printer.noSpaces.copy(dropNullValues = true)
  protected val pq: EventsByTagQuery =
    PersistenceQuery(as).readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)

  def projectRoutes(project: ProjectResource): Route = {

    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/resources/{org}/{project}/events") {
        authorizeFor(project.value.path, read)(caller) {
          complete(source(s"project=${project.uuid}", offset))
        }
      }
    }
  }

  def organizationRoutes(org: OrganizationResource): Route =
    lastEventId { offset =>
      operationName(s"/${config.http.prefix}/resources/{org}/events") {
        authorizeFor(/ + org.value.label, read)(caller) {
          complete(source(s"org=${org.uuid}", offset))
        }
      }
    }

  protected def source(tag: String, offset: Offset): Source[ServerSentEvent, NotUsed] =
    pq.eventsByTag(tag, offset)
      .flatMapConcat(ee => Source(eventToSse(ee).toList))
      .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)

  private def lastEventId: Directive1[Offset] =
    optionalHeaderValueByName(`Last-Event-ID`.name)
      .map(_.map(id => `Last-Event-ID`(id)))
      .flatMap {
        case Some(header) =>
          Try[Offset](TimeBasedUUID(UUID.fromString(header.id))) orElse Try(Sequence(header.id.toLong)) match {
            case Success(value) => provide(value)
            case Failure(_)     => reject(validationRejection("The value of the `Last-Event-ID` header is not valid."))
          }
        case None         => provide(NoOffset)
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

  protected def eventToSse(envelope: EventEnvelope): Option[ServerSentEvent] =
    envelope.event match {
      case value: Event => Some(aToSse(value, envelope.offset))
      case _            => None
    }
}
