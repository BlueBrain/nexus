package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, PersistenceQuery, TimeBasedUUID}
import cats.effect.ExitCase
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import com.typesafe.scalalogging.Logger
import fs2._
import monix.bio.{Task, UIO}

/**
  * A log of ordered events for uniquely identifiable entities and independent from the storage layer
  */
trait EventLog[M] {

  /**
    * The flavour of the event log
    */
  def flavour: DatabaseFlavour

  /**
    * Default offset to fetch from the beginning
    */
  def firstOffset: Offset

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
  import akka.actor.typed.ActorSystem
  import akka.persistence.query.scaladsl.{ReadJournal, _}
  import akka.stream.scaladsl.Source
  import streamz.converter._

  private val logger: Logger = Logger[EventLog.type]

  type Journal = ReadJournal
    with PersistenceIdsQuery
    with CurrentPersistenceIdsQuery
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery
    with EventsByTagQuery
    with CurrentEventsByTagQuery

  /**
    * Implementation of [[EventLog]] based on Akka Persistence.
    *
    * @param flavour     the flavour of the event log
    * @param firstOffset the default offset to fetch from the beginning
    * @param readJournal the underlying akka persistence journal
    * @param f the function to transform envelopes
    * @param as the actor system
    * @tparam RJ     the underlying journal typr (Cassandra / JDBC / ...)
    * @tparam M      the event type
    */
  private class AkkaEventLog[RJ <: Journal, M](
      val flavour: DatabaseFlavour,
      val firstOffset: Offset,
      readJournal: RJ,
      f: EventEnvelope => UIO[Option[M]]
  )(implicit as: ActorSystem[Nothing])
      extends EventLog[M] {

    private def toStream[A](source: Source[A, _], description: String) =
      source
        .toStream[Task](_ => ())
        .handleErrorWith { e =>
          logger.error(s"EventLog stream $description encountered an error.", e)
          Stream.raiseError[Task](e)
        }
        .onFinalizeCase {
          case ExitCase.Completed =>
            Task.delay(logger.debug(s"EventLog stream $description has been successfully completed."))
          case ExitCase.Error(e)  => Task.delay(logger.error(s"EventLog stream $description has failed.", e))
          case ExitCase.Canceled  => Task.delay(logger.debug(s"EventLog stream $description got cancelled."))
        }

    override def currentPersistenceIds: Stream[Task, String] =
      toStream(
        readJournal.currentPersistenceIds(),
        "currentPersistenceIds"
      )

    override def persistenceIds: Stream[Task, String] =
      toStream(
        readJournal.persistenceIds(),
        "persistenceIds"
      )

    override def eventsByPersistenceId(
        persistenceId: String,
        fromSequenceNr: Long,
        toSequenceNr: Long
    ): Stream[Task, M] =
      toStream(
        readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr),
        s"eventsByPersistenceId($persistenceId, $fromSequenceNr, $toSequenceNr)"
      ).evalMapFilter(f)

    override def currentEventsByPersistenceId(
        persistenceId: String,
        fromSequenceNr: Long,
        toSequenceNr: Long
    ): Stream[Task, M] =
      toStream(
        readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr),
        s"currentEventsByPersistenceId($persistenceId, $fromSequenceNr, $toSequenceNr)"
      ).evalMapFilter(f)

    override def eventsByTag(tag: String, offset: Offset): Stream[Task, M] =
      toStream(
        readJournal.eventsByTag(tag, offset),
        s"eventsByTag($tag, $offset)"
      ).evalMapFilter(f)

    override def currentEventsByTag(tag: String, offset: Offset): Stream[Task, M] =
      toStream(
        readJournal.currentEventsByTag(tag, offset),
        s"currentEventsByTag($tag, $offset)"
      ).evalMapFilter(f)
  }

  /**
    * Create an event log relying on akka-persistence-cassandra
    * @param f the transformation we want to apply to the [[EventEnvelope]]
    */
  def cassandraEventLog[M](
      f: EventEnvelope => UIO[Option[M]]
  )(implicit as: ActorSystem[Nothing]): Task[EventLog[M]] = Task.delay {
    val cassandraReadJournal =
      PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    new AkkaEventLog(
      DatabaseFlavour.Cassandra,
      TimeBasedUUID(cassandraReadJournal.firstOffset),
      cassandraReadJournal,
      f
    )
  }

  /**
    * Create an event log relying on akka-persistence-jdbc
    * @param f the transformation we want to apply to the [[EventEnvelope]]
    */
  def postgresEventLog[M](
      f: EventEnvelope => UIO[Option[M]]
  )(implicit as: ActorSystem[Nothing]): Task[EventLog[M]] = Task.delay {
    new AkkaEventLog(
      DatabaseFlavour.Postgres,
      NoOffset,
      PersistenceQuery(as).readJournalFor[Journal](JdbcReadJournal.Identifier),
      f
    )
  }

}
