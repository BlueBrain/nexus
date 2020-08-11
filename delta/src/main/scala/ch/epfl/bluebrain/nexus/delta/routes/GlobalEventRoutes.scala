package ch.epfl.bluebrain.nexus.delta.routes

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
import ch.epfl.bluebrain.nexus.admin.config.Permissions._
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent.JsonLd._
import ch.epfl.bluebrain.nexus.admin.persistence.TaggingAdapter.{OrganizationTag, ProjectTag}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent.JsonLd._
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.delta.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.acls.{AclEvent, Acls}
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent.JsonLd._
import ch.epfl.bluebrain.nexus.iam.realms.{RealmEvent, Realms}
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.persistence.TaggingAdapter.EventTag
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import io.circe.syntax._
import io.circe.{Encoder, Printer}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import ch.epfl.bluebrain.nexus.iam.{acls => aclsp, realms => realmsp, permissions}

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * Server Sent Events routes for organizations, projects and the entire event log.
  */
class GlobalEventRoutes(acls: Acls[Task], realms: Realms[Task])(implicit
    as: ActorSystem,
    pc: PersistenceConfig,
    http: HttpConfig
) extends AuthDirectives(acls, realms) {

  private val pq: EventsByTagQuery        = PersistenceQuery(as).readJournalFor[EventsByTagQuery](pc.queryJournalPlugin)
  private val printer: Printer            = Printer.noSpaces.copy(dropNullValues = true)
  private val prefix                      = http.prefix
  private[routes] val resRead: Permission = Permission.unsafe("resources/read")

  // format: off
  def routes: Route =
    concat(
      routesFor("events", EventTag, s"/$prefix/events", events.read, eventToSse),
      routesFor("resources" / "events", EventTag, "/resources/events", resRead, typedEventToSse[Event]),
      routesFor("orgs" / "events", OrganizationTag, "/orgs/events", orgs.read, typedEventToSse[OrganizationEvent]),
      routesFor("projects" / "events", ProjectTag, "/projects/events", projects.read, typedEventToSse[ProjectEvent]),
      routesFor("acls" / "events", "/acls/events", AclEventTag, aclsp.read, typedEventToSse[AclEvent]),
      routesFor("permissions" / "events", "/permissions/events", PermissionsEventTag, permissions.read, typedEventToSse[PermissionsEvent]),
      routesFor("realms" / "events", "/realms/events", RealmEventTag, realmsp.read, typedEventToSse[RealmEvent])
    )
  // format: on

  private def routesFor(
      pm: PathMatcher0,
      tag: String,
      opName: String,
      perms: Permission,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Route =
    (get & pathPrefix(prefix / pm) & pathEndOrSingleSlash) {
      operationName(s"$prefix/$opName") {
        extractCaller { caller =>
          authorizeFor(permission = perms)(caller) {
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
      case value: AclEvent          => Some(aToSse(value, envelope.offset))
      case value: RealmEvent        => Some(aToSse(value, envelope.offset))
      case value: PermissionsEvent  => Some(aToSse(value, envelope.offset))
      case value: Event             => Some(aToSse(value, envelope.offset))
      case _                        => None
    }

  private def typedEventToSse[A: Encoder](envelope: EventEnvelope)(implicit A: ClassTag[A]): Option[ServerSentEvent] =
    envelope.event match {
      case A(a) => Some(aToSse(a, envelope.offset))
      case _    => None
    }
}
