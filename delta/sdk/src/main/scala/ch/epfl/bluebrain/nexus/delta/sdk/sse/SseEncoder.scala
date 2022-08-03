package ch.epfl.bluebrain.nexus.delta.sdk.sse

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import io.circe.{Decoder, Encoder, JsonObject}

import scala.reflect.ClassTag

abstract class SseEncoder[E <: Event](implicit c: ClassTag[E]) {
  def databaseDecoder: Decoder[E]

  def entityType: EntityType

  def handlesScopedEvent: Boolean = c.runtimeClass.isAssignableFrom(classOf[ScopedEvent])

  def selectors: Set[Label]

  def sseEncoder: Encoder.AsObject[E]

  def toSse: Decoder[SseData] = databaseDecoder.map { event =>
    val data = sseEncoder.encodeObject(event)
    event match {
      case e: ScopedEvent => SseData(ClassUtils.simpleName(e), Some(e.project), data)
      case e              => SseData(ClassUtils.simpleName(e), None, data)
    }

  }

}

object SseEncoder {

  final case class SseData(tpe: String, project: Option[ProjectRef], data: JsonObject)

}
