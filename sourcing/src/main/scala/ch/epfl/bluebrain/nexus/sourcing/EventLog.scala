package ch.epfl.bluebrain.nexus.sourcing

import cats.Functor
import cats.syntax.all._

/**
  * A log of ordered events for uniquely identifiable entities.
  *
  * @tparam F[_]       the event log effect type
  * @tparam Identifier the type of identifier for entities
  * @tparam Event      the event type
  */
trait EventLog[F[_], Identifier, Event] {

  /**
    * @return the name of this event log.
    */
  def name: String

  /**
    * The last sequence number of the entity identified by __id__.
    *
    * @param id the entity identifier
    * @return the last sequence number for the entity identified by __id__
    */
  def lastSequenceNr(id: Identifier): F[Long]

  /**
    * Appends the argument __event__ to the end of the event log of the entity identified by __id__.
    *
    * @param id    the entity identifier
    * @param event the event to append
    * @return the sequence number that corresponds to the appended event
    */
  def append(id: Identifier, event: Event): F[Long]

  /**
    * Folds over the event log of the entity identified by __id__ applying the binary operator to the accumulated
    * value and each of the events in sequence. The direction of the fold is the insertion order.
    *
    * @param id the entity identifier
    * @param z  the start value (zero)
    * @param op the binary operator
    * @tparam B the result type of the binary operator
    * @return the accumulated value
    */
  def foldLeft[B](id: Identifier, z: B)(op: (B, Event) => B): F[B]

  /**
    * Whether an event log for entity identified by __id__ exists (any events recorded for the entity).
    *
    * @param id the entity identifier
    * @param F  a functor for __F__
    * @return true in __F__ if there are events recorded for entity with id __id__, false in __F__ otherwise
    */
  def exists(id: Identifier)(implicit F: Functor[F]): F[Boolean] =
    lastSequenceNr(id).map(_ > 0)
}
