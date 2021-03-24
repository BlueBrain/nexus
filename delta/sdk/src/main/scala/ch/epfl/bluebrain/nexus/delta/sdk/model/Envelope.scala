package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.persistence.query.Offset
import cats.Functor
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.SuccessMessage

import java.time.Instant

/**
  * A typed event envelope.
  *
  * @param event         the event
  * @param instant       the instant when the event was issued
  * @param eventType     the event qualifier
  * @param offset        the event offset
  * @param persistenceId the event persistence id
  * @param sequenceNr    the event sequence number
  */
final case class Envelope[E](
    event: E,
    instant: Instant,
    eventType: String,
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long
) {

  /**
    * Converts the current envelope to a [[SuccessMessage]]
    */
  def toMessage: SuccessMessage[E] =
    SuccessMessage(offset, instant, persistenceId, sequenceNr, event, Vector.empty)
}

object Envelope {

  def apply[E <: Event](
      event: E,
      offset: Offset,
      persistenceId: String,
      sequenceNr: Long
  ): Envelope[E] =
    Envelope(event, event.instant, ClassUtils.simpleName(event), offset, persistenceId, sequenceNr)

  implicit val envelopeFunctor: Functor[Envelope] = new Functor[Envelope] {
    override def map[A, B](fa: Envelope[A])(f: A => B): Envelope[B] = fa.copy(event = f(fa.event))
  }
}
