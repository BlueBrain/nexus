package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.persistence.query.{EventEnvelope, Offset}
import cats.{Eq, Functor}
import io.circe.{Encoder, Json}

import scala.reflect.ClassTag

/*

 */
sealed trait Message[+A] {
  def offset: Offset
  def persistenceId: String
  def sequenceNr: Long
}

sealed trait SkippedMessage extends Message[Nothing]

sealed trait ErrorMessage extends SkippedMessage

final case class FailureMessage(offset: Offset,
                                persistenceId: String,
                                sequenceNr: Long,
                                value: Json,
                                throwable: Throwable) extends ErrorMessage

final case class CastFailedMessage(offset: Offset,
                                   persistenceId: String,
                                   sequenceNr: Long,
                                   expectedClassname: String,
                                   encounteredClassName: String) extends ErrorMessage

final case class DiscardedMessage(offset: Offset,
                                  persistenceId: String,
                                  sequenceNr: Long) extends SkippedMessage

final case class SuccessMessage[A](offset: Offset,
                                   persistenceId: String,
                                   sequenceNr: Long,
                                   value: A) extends Message[A] {

  def discarded: DiscardedMessage = DiscardedMessage(offset, persistenceId, sequenceNr)

  def failed(throwable: Throwable)(implicit encoder: Encoder[A]): FailureMessage =
    FailureMessage(offset, persistenceId, sequenceNr, encoder(value), throwable)

}

object Message {

  /**
    * Parse an akka-persistence in a message
    * @param envelope the envelope to parse
    * @tparam A the expected type for the event
    * @return a success message if it is fine or a castfailed message if the event value is not of type A
    */
  def apply[A: ClassTag](envelope: EventEnvelope): Message[A] = {
    val Value = implicitly[ClassTag[A]]
    envelope.event match {
      case Value(value) =>
        SuccessMessage(envelope.offset, envelope.persistenceId, envelope.sequenceNr, value)
      case v            => CastFailedMessage(envelope.offset,
                                             envelope.persistenceId,
                                             envelope.sequenceNr,
                                             Value.runtimeClass.getTypeName,
                                             v.getClass.getName)
    }
  }

  implicit val functorMessage: Functor[Message] = new Functor[Message] {
    override def map[A, B](m: Message[A])(f: A => B): Message[B] =
      m match {
        case s: SuccessMessage[A] => s.copy(value = f(s.value))
        case e: SkippedMessage    => e
      }
  }

  implicit val samePersistenceId: Eq[Message[_]] =
    (x: Message[_], y: Message[_]) => x.persistenceId == y.persistenceId
}
