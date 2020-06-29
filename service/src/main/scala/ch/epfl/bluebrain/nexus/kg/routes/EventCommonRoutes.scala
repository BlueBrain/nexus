package ch.epfl.bluebrain.nexus.kg.routes
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{optionalHeaderValueByName, provide, reject}
import akka.persistence.query._
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import io.circe.syntax._
import io.circe.{Encoder, Printer}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * Defines commons methods for event routes.
  */
private[routes] trait EventCommonRoutes {

  implicit def as: ActorSystem

  implicit def config: ServiceConfig

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  protected val pq: EventsByTagQuery =
    PersistenceQuery(as).readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)

  /**
    * Create a [[Source]] of [[ServerSentEvent]]s.
    *
    * @param tag    tag to filter events by
    * @param offset offset of the last consumed event
    * @return       [[Source]] of [[ServerSentEvent]]s
    */
  protected def source(
      tag: String,
      offset: Offset
  )(implicit enc: Encoder[Event]): Source[ServerSentEvent, NotUsed] =
    pq.eventsByTag(tag, offset)
      .flatMapConcat(ee => Source(eventToSse(ee).toList))
      .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)

  /**
    * Extract [[Offset]] from `Last-Event-ID` HTTP header.
    */
  protected def lastEventId: Directive1[Offset] =
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

  protected def eventToSse(envelope: EventEnvelope)(implicit enc: Encoder[Event]): Option[ServerSentEvent] =
    envelope.event match {
      case value: Event => Some(aToSse(value, envelope.offset))
      case _            => None
    }
}
