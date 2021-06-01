package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{EventEnvelope, Offset}
import cats.{FlatMap, Functor, FunctorFilter}
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax._

import java.time.Instant
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
  * @param throwable the exception which has been raised during the processing
  */
final case class FailureMessage[A](
    offset: Offset,
    timestamp: Instant,
    persistenceId: String,
    sequenceNr: Long,
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
) extends ErrorMessage {

  def errorMessage: String = s"Class '$expectedClassname' was expected, '$encounteredClassName' was encountered."
}

/**
  * Message when it has been filtered out during the projection process
  */
final case class DiscardedMessage(
    offset: Offset,
    timestamp: Instant,
    persistenceId: String,
    sequenceNr: Long,
    skippedRevisions: Long = 0L
) extends SkippedMessage

/**
  * Message which hasn't been filtered out nor been victim of a failure
  * during the projection process
  *
  * @param value the value of the message
  */
final case class SuccessMessage[A](
    offset: Offset,
    timestamp: Instant,
    persistenceId: String,
    sequenceNr: Long,
    value: A,
    warnings: Vector[RunResult.Warning],
    skippedRevisions: Long = 0L
) extends Message[A] {

  def discarded: DiscardedMessage = DiscardedMessage(offset, timestamp, persistenceId, sequenceNr, skippedRevisions)

  def failed(throwable: Throwable): FailureMessage[A] =
    FailureMessage(offset, timestamp, persistenceId, sequenceNr, throwable)

  def addWarning(warning: RunResult.Warning): SuccessMessage[A] = copy(warnings = warnings :+ warning)

  def warningMessage: String = warnings.map(_.message).mkString("\n")
}

object Message {

  /**
    * Parse an akka-persistence in a message
    * @param envelope the envelope to parse
    * @return a success message if it is fine or a castfailed message if the event value is not of type A
    */
  def apply[A: ClassTag](envelope: EventEnvelope)(implicit timestamp: Lens[A, Instant]): Message[A] = {
    val Value = implicitly[ClassTag[A]]
    envelope.event match {
      case Value(value) =>
        SuccessMessage(
          envelope.offset,
          timestamp.get(value),
          envelope.persistenceId,
          envelope.sequenceNr,
          value,
          Vector.empty
        )
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

  implicit val functorFilter: FunctorFilter[Message] = new FunctorFilter[Message] {
    override def functor: Functor[Message] = new Functor[Message] {
      override def map[A, B](m: Message[A])(f: A => B): Message[B] =
        m match {
          case s: SuccessMessage[A] => s.copy(value = f(s.value))
          case e: SkippedMessage    => e
        }
    }

    override def mapFilter[A, B](fa: Message[A])(f: A => Option[B]): Message[B] =
      fa match {
        case s: SuccessMessage[A] => f(s.value).fold[Message[B]](s.discarded)(v => s.copy(value = v))
        case e: SkippedMessage    => e
      }
  }

  implicit val flatMapMessage: FlatMap[Message] = new FlatMap[Message] {
    override def flatMap[A, B](fa: Message[A])(f: A => Message[B]): Message[B] =
      fa match {
        case s: SuccessMessage[A] => f(s.value)
        case e: SkippedMessage    => e
      }

    @annotation.tailrec
    override def tailRecM[A, B](init: A)(f: A => Message[Either[A, B]]): Message[B] =
      f(init) match {
        case e: SkippedMessage                                  => e
        case s @ SuccessMessage(_, _, _, _, Right(value), _, _) => s.copy(value = value)
        case SuccessMessage(_, _, _, _, Left(err), _, _)        => tailRecM(err)(f)
      }

    override def map[A, B](fa: Message[A])(f: A => B): Message[B] = functorFilter.functor.map(fa)(f)
  }

  implicit val functorSuccessMessage: Functor[SuccessMessage] = new Functor[SuccessMessage] {
    override def map[A, B](m: SuccessMessage[A])(f: A => B): SuccessMessage[B] =
      m.copy(value = f(m.value))
  }

  def always[A]: Message[A] => Boolean = (_: Message[A]) => true

  def filterOffset[A](offset: Offset): Message[A] => Boolean =
    (m: Message[A]) => m.offset.gt(offset)
}
