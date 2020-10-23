package ch.epfl.bluebrain.nexus.sourcing

import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import fs2._
import monix.bio.{Task, UIO}

import scala.concurrent.ExecutionContext

/**
  * A log of ordered events for uniquely identifiable entities and independent from the storage layer
  */
trait EventLog[M] {

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
  import akka.stream.Materializer
  import akka.stream.scaladsl.Source
  import streamz.converter._

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
    * @param readJournal the underlying akka persistence journal
    * @param f the function to transform envelopes
    * @param as the actor system
    * @tparam RJ     the underlying journal typr (Cassandra / JDBC / ...)
    * @tparam M      the event type
    */
  private class AkkaEventLog[RJ <: Journal, M](
      readJournal: RJ,
      f: EventEnvelope => UIO[Option[M]]
  )(implicit as: ActorSystem[Nothing])
      extends EventLog[M] {

    implicit val executionContext: ExecutionContext = as.executionContext
    implicit val materializer: Materializer         = Materializer.createMaterializer(as)

    private def toStream[A](source: Source[A, _]) =
      source.toStream[Task](_ => ())

    override def currentPersistenceIds: Stream[Task, String] = toStream(readJournal.currentPersistenceIds())

    override def persistenceIds: Stream[Task, String] = toStream(readJournal.persistenceIds())

    override def eventsByPersistenceId(
        persistenceId: String,
        fromSequenceNr: Long,
        toSequenceNr: Long
    ): Stream[Task, M] =
      toStream(readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).evalMapFilter(f)

    override def currentEventsByPersistenceId(
        persistenceId: String,
        fromSequenceNr: Long,
        toSequenceNr: Long
    ): Stream[Task, M] =
      toStream(readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).evalMapFilter(f)

    override def eventsByTag(tag: String, offset: Offset): Stream[Task, M] =
      toStream(readJournal.eventsByTag(tag, offset)).evalMapFilter(f)

    override def currentEventsByTag(tag: String, offset: Offset): Stream[Task, M] =
      toStream(readJournal.currentEventsByTag(tag, offset)).evalMapFilter(f)
  }

  /**
    * Create an event log relying on akka-persistence-cassandra
    * @param f the transformation we want to apply to the [[EventEnvelope]]
    */
  def cassandraEventLog[M](
      f: EventEnvelope => UIO[Option[M]]
  )(implicit as: ActorSystem[Nothing]): Task[EventLog[M]] =
    eventLog(f, CassandraReadJournal.Identifier)

  /**
    * Create an event log relying on akka-persistence-jdbc
    * @param f the transformation we want to apply to the [[EventEnvelope]]
    */
  def postgresEventLog[M](
      f: EventEnvelope => UIO[Option[M]]
  )(implicit as: ActorSystem[Nothing]): Task[EventLog[M]] =
    eventLog(f, JdbcReadJournal.Identifier)

  /**
    * Create an event log using the provided journal identifier.
    *
    * @param f                 the transformation we want to apply to the [[EventEnvelope]]
    * @param journalIdentifier the journal identifier
    */
  def eventLog[RJ <: Journal, M](
      f: EventEnvelope => UIO[Option[M]],
      journalIdentifier: String
  )(implicit as: ActorSystem[Nothing]): Task[EventLog[M]] =
    Task.delay {
      new AkkaEventLog(PersistenceQuery(as).readJournalFor[RJ](journalIdentifier), f)
    }

}
