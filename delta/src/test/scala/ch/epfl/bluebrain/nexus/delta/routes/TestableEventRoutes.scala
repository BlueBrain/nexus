package ch.epfl.bluebrain.nexus.delta.routes

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import monix.eval.Task

//noinspection TypeAnnotation
class TestableEventRoutes(
    events: List[Any],
    acls: Acls[Task],
    realms: Realms[Task]
)(implicit as: ActorSystem, hc: HttpConfig, pc: PersistenceConfig)
    extends GlobalEventRoutes(acls, realms) {

  private val envelopes = events.zipWithIndex.map {
    case (ev, idx) => EventEnvelope(Sequence(idx.toLong), "persistenceid", 1L, ev, idx.toLong)
  }

  override protected def source(
      tag: String,
      offset: Offset,
      toSse: EventEnvelope => Option[ServerSentEvent]
  ): Source[ServerSentEvent, NotUsed] = {
    val toDrop = offset match {
      case NoOffset    => 0
      case Sequence(v) => v + 1
    }
    Source(envelopes).drop(toDrop).flatMapConcat(ee => Source(toSse(ee).toList))
  }
}
