package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.persistence.query._
import fs2._
import monix.bio.Task

/**
  * A log of ordered events for uniquely identifiable entities and independent from the storage layer
  */
trait EventLog[M] {

  /**
    * Configuration related to the event log
    */
  def config: EventLogConfig

  /**
    * [[akka.persistence.query.scaladsl.PersistenceIdsQuery#currentPersistenceIds]] in a fs2 stream
    */
  def persistenceIds: Stream[Task, String]

  /**
    * [[akka.persistence.query.scaladsl.CurrentPersistenceIdsQuery#currentPersistenceIds]] in a fs2 stream
    */
  def currentPersistenceIds: Stream[Task, String]

  /**
    * [[akka.persistence.query.scaladsl.EventsByPersistenceIdQuery#eventsByPersistenceId]] in a fs2 stream
    */
  def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Stream[Task, M]

  /**
    * [[akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery#currentEventsByPersistenceId]] in a fs2 stream
    */
  def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Stream[Task, M]

  /**
    * [[akka.persistence.query.scaladsl.EventsByTagQuery#eventsByTag]] in a fs2 stream
    */
  def eventsByTag(tag: String, offset: Offset): Stream[Task, M]

  /**
    * [[akka.persistence.query.scaladsl.CurrentEventsByTagQuery#currentEventsByTag]] in a fs2 stream
    */
  def currentEventsByTag(tag: String, offset: Offset): Stream[Task, M]
}
object EventLog {
  import akka.persistence.query.scaladsl._

  type Journal = ReadJournal
    with PersistenceIdsQuery
    with CurrentPersistenceIdsQuery
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery
    with EventsByTagQuery
    with CurrentEventsByTagQuery

}
