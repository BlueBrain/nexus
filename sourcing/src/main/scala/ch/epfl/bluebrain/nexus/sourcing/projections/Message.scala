package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{EventEnvelope, NoOffset, Offset}
import cats.Functor
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.ProgressStatus
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.ProgressStatus.Failed

/**
  * A message with context information. The context information provides the progress status.
  *
  * @param progress       map where each key is a progressId and each value is its status
  * @param value          the value wrapped in the message
  * @param currProgressId the current progress Id
  * @param offset         the offset of the message
  * @param persistenceId  the persistenceId of the message
  * @param sequenceNr     the sequence number of the message
  * @tparam A the generic type of the message value
  */
final case class Message[A](
    progress: Map[String, ProgressStatus],
    value: A,
    currProgressId: String,
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long
) {

  /**
    * Changes the ''currProgressId'' to the passed value
    *
    * @param progressId the new ''currProgressId''
    * @return a copy of the message with the ''currProgressId'' as the provided ''progressId''
    */
  def nextProgress(progressId: String): Message[A] =
    copy(
      progress = progress + (progressId -> progress.getOrElse(progressId, ProgressStatus.Passed)),
      currProgressId = progressId
    )

  def unit: Message[Unit] =
    copy(value = ())

  /**
    * Adds failed to the current message progress.
    *
    * @return a copy of the current message with failed added to the progress map
    */
  def fail(error: String): Message[A] =
    copy(progress = progress + (currProgressId -> ProgressStatus.Failed(error)))

  /**
    * Adds discard to the current message progress. Discard won't be added if the current progress is Failed.
    *
    * @return a copy of the current message with discard added to the progress map
    */
  def discard(): Message[A] =
    if (progress.getOrElse(currProgressId, ProgressStatus.Passed).failed) this
    else copy(progress = progress + (currProgressId -> ProgressStatus.Discarded))

  /**
    * Combine two messages progress
    */
  def addProgressOf[B](that: Message[B]): Message[B] =
    that.copy(progress = progress ++ that.progress)

  /**
    * Fetch the failures from the progress map
    */
  def failures(): Map[String, Failed] = progress.collect { case (id, v: Failed) => (id, v) }

  /**
    * Checks whether the current message has the same identifiers as the passed one.
    *
    * @param that the message to compare against the current
    * @tparam B the generic type of the message
    * @return true if both have the same identifier, false otherwise
    */
  def sameIdentifier[B](that: Message[B]): Boolean =
    sequenceNr == that.sequenceNr && currProgressId == that.currProgressId && offset == that.offset
}

object Message {

  final def apply[A](value: A, progressId: String, offset: Offset, persistenceId: String, rev: Long): Message[A] =
    Message(Map(progressId -> ProgressStatus.Passed), value, progressId, offset, persistenceId, rev)

  final def apply(envelope: EventEnvelope, progressId: String): Message[Any] =
    apply(envelope.event, progressId, envelope.offset, envelope.persistenceId, envelope.sequenceNr)

  val empty: Message[Unit] =
    Message(Map.empty, (), "", NoOffset, "", 0L)

  implicit val functorMessage: Functor[Message] = new Functor[Message] {
    override def map[A, B](fa: Message[A])(f: A => B): Message[B] = fa.copy(value = f(fa.value))
  }
}
