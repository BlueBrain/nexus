package ch.epfl.bluebrain.nexus.iam.routes

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.server.Rejections._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, PathMatcher0, Route}
import akka.persistence.query._
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.acls.{AclEvent, Acls}
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.realms.{RealmEvent, Realms}
import ch.epfl.bluebrain.nexus.iam.routes.EventRoutes._
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.iam.{acls => aclsp, permissions => permissionsp, realms => realmsp}
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import io.circe.syntax._
import io.circe.{Encoder, Printer}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * The event stream routes.
  *
  * @param acls   the acls api
  * @param realms the realms api
  */
class EventRoutes(acls: Acls[Task], realms: Realms[Task])(implicit
    as: ActorSystem,
    hc: HttpConfig,
    pc: PersistenceConfig
) extends AuthDirectives(acls, realms) {

  private val pq: EventsByTagQuery = PersistenceQuery(as).readJournalFor[EventsByTagQuery](pc.queryJournalPlugin)
  private val printer: Printer     = Printer.noSpaces.copy(dropNullValues = true)

  def routes: Route =
    // format: off
    concat(
      routesFor("acls" / "events", s"/${hc.prefix}/acls/events", aclEventTag, aclsp.read, typedEventToSse[AclEvent]),
      routesFor("permissions" / "events", s"/${hc.prefix}/permissions/events", permissionsEventTag, permissionsp.read, typedEventToSse[PermissionsEvent]),
      routesFor("realms" / "events", s"/${hc.prefix}/realms/events", realmEventTag, realmsp.read, typedEventToSse[RealmEvent]),
      routesFor("events", s"/${hc.prefix}/events", eventTag, eventsRead, eventToSse)
    )
  // format: on

  private def routesFor(
      pm: PathMatcher0,
      opName: String,
      tag: String,
      permission: Permission,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Route =
    (pathPrefix(pm) & pathEndOrSingleSlash) {
      operationName(opName) {
        extractCaller { caller =>
          authorizeFor(permission = permission)(caller) {
            lastEventId { offset => complete(source(tag, offset, toSse)) }
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
        case None         => provide(NoOffset)
      }

  private def aToSse[A: Encoder](a: A, offset: Offset): ServerSentEvent = {
    val json = a.asJson.sortKeys(ServiceConfig.orderedKeys)
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
      case value: AclEvent         => Some(aToSse(value, envelope.offset))
      case value: RealmEvent       => Some(aToSse(value, envelope.offset))
      case value: PermissionsEvent => Some(aToSse(value, envelope.offset))
      case _                       => None
    }

  private def typedEventToSse[A: Encoder](envelope: EventEnvelope)(implicit A: ClassTag[A]): Option[ServerSentEvent] =
    envelope.event match {
      case A(a) => Some(aToSse(a, envelope.offset))
      case _    => None
    }
}

object EventRoutes {
  // read permissions for the global event log
  final val eventsRead = Permission.unsafe("events/read")
}
