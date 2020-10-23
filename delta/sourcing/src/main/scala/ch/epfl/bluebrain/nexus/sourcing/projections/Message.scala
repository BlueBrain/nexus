package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{EventEnvelope, Offset}
import cats.Functor
import ch.epfl.bluebrain.nexus.sourcing.projections.syntax._

import scala.reflect.ClassTag

/**
  * Message going though a projection
  */
sealed trait Message[+A] extends Product with Serializable {
  def offset: Offset
  def persistenceId: String
  def sequenceNr: Long
}

/**
  * Message that has been filtered out or raised an error during the projection
  * process
  */
sealed trait SkippedMessage extends Message[Nothing]

/**
  * Message when its processing failed at a point of the projection
  * See [[FailureMessage]] and [[CastFailedMessage]]
  */
sealed trait ErrorMessage extends SkippedMessage

/**
  * Message when its processing failed at a point of the projection
  * After the failure, it keeps untouched
  * until the end of the processing
  *
  * @param value the value of the message
  * @param throwable the exception which has been raised during the processing
  */
final case class FailureMessage[A](
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long,
    value: A,
    throwable: Throwable
) extends ErrorMessage

/**
  * Message when it suffers a ClassCastException when parsing the event in
  * the akka-persistence enveloppe
  *
  * @param expectedClassname the expected classname for the value
  * @param encounteredClassName the classname we got for the value
  */
final case class CastFailedMessage(
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long,
    expectedClassname: String,
    encounteredClassName: String
) extends ErrorMessage

/**
  * Message when it has been filtered out during the projection process
  */
final case class DiscardedMessage(offset: Offset, persistenceId: String, sequenceNr: Long) extends SkippedMessage

/**
  * Message which hasn't been filtered out nor been victim of a failure
  * during the projection process
  *
  * @param value the value of the message
  */
final case class SuccessMessage[A](offset: Offset, persistenceId: String, sequenceNr: Long, value: A)
    extends Message[A] {

  def discarded: DiscardedMessage = DiscardedMessage(offset, persistenceId, sequenceNr)

  def failed(throwable: Throwable): FailureMessage[A] =
    FailureMessage(offset, persistenceId, sequenceNr, value, throwable)

}

object Message {

  /**
    * Parse an akka-persistence in a message
    * @param envelope the envelope to parse
    * @return a success message if it is fine or a castfailed message if the event value is not of type A
    */
  def apply[A: ClassTag](envelope: EventEnvelope): Message[A] = {
    val Value = implicitly[ClassTag[A]]
    envelope.event match {
      case Value(value) =>
        SuccessMessage(envelope.offset, envelope.persistenceId, envelope.sequenceNr, value)
      case v            =>
        CastFailedMessage(
          envelope.offset,
          envelope.persistenceId,
          envelope.sequenceNr,
          Value.runtimeClass.getTypeName,
          v.getClass.getName
        )
    }
  }

  implicit val functorMessage: Functor[Message] = new Functor[Message] {
    override def map[A, B](m: Message[A])(f: A => B): Message[B] =
      m match {
        case s: SuccessMessage[A] => s.copy(value = f(s.value))
        case e: SkippedMessage    => e
      }
  }

  def always[A]: Message[A] => Boolean = (_: Message[A]) => true

  def filterOffset[A](offset: Offset): Message[A] => Boolean =
    (m: Message[A]) => m.offset.gt(offset)
}
