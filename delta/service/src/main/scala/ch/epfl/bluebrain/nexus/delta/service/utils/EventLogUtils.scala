package ch.epfl.bluebrain.nexus.delta.service.utils

import akka.persistence.query.{EventEnvelope, Offset}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import com.typesafe.scalalogging.Logger
import monix.bio.UIO

import scala.reflect.ClassTag

object EventLogUtils {

  private val logger: Logger = Logger("EventLog")

  /**
    * Attempts to convert a generic event envelope to a type one.
    * @param envelope the generic event envelope
    */
  def toEnvelope[E <: Event](envelope: EventEnvelope)(implicit Event: ClassTag[E]): UIO[Option[Envelope[E]]] =
    envelope match {
      case ee @ EventEnvelope(offset: Offset, persistenceId, sequenceNr, Event(value)) =>
        UIO.pure(Some(Envelope(value, ClassUtils.simpleName(value), offset, persistenceId, sequenceNr, ee.timestamp)))
      case _                                                                           =>
        UIO(
          logger.warn(
            s"Failed to match envelope value '${envelope.event}' to class '${Event.runtimeClass.getCanonicalName}'"
          )
        ) >> UIO.pure(None)
    }
}
