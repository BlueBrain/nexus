package ch.epfl.bluebrain.nexus.sourcingnew

import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import fs2._
import monix.bio.Task

import scala.concurrent.ExecutionContext

/**
  * A log of ordered events for uniquely identifiable entities.
  *
  */
trait EventLog[M] {
  def currentPersistenceIds: Stream[Task, String]
  def persistenceIds: Stream[Task, String]
  def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Stream[Task, M]
  def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Stream[Task, M]
  def eventsByTag(tag: String, offset: Offset): Stream[Task, M]
  def currentEventsByTag(tag: String, offset: Offset): Stream[Task, M]
}
object EventLog   {
  import akka.actor.typed.ActorSystem
  import akka.persistence.query.Offset
  import akka.persistence.query.scaladsl.{ReadJournal, _}
  import akka.stream.Materializer
  import akka.stream.scaladsl.Source
  import streamz.converter._

  /**
    * Implementation of [[EventLog]] based on Akka Persistence
    * @param readJournal the underlying akka persistence journal
    * @param f the function to transform envelopes
    * @param as the actor system
    * @tparam RJ     the underlying journal typr (Cassandra / JDBC / ...)
    * @tparam M      the event type
    */
  class AkkaEventLog[
      RJ <: ReadJournal with PersistenceIdsQuery with CurrentPersistenceIdsQuery with EventsByPersistenceIdQuery with CurrentEventsByPersistenceIdQuery with EventsByTagQuery with CurrentEventsByTagQuery,
      M
  ](readJournal: RJ, f: EventEnvelope => M)(implicit as: ActorSystem[Nothing])
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
      toStream(readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).map(f)

    override def currentEventsByPersistenceId(
        persistenceId: String,
        fromSequenceNr: Long,
        toSequenceNr: Long
    ): Stream[Task, M] =
      toStream(readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).map(f)

    override def eventsByTag(tag: String, offset: Offset): Stream[Task, M] =
      toStream(readJournal.eventsByTag(tag, offset)).map(f)

    override def currentEventsByTag(tag: String, offset: Offset): Stream[Task, M] =
      toStream(readJournal.currentEventsByTag(tag, offset)).map(f)
  }

  def cassandraEventLog[M](f: EventEnvelope => M)(implicit as: ActorSystem[Nothing]): EventLog[M] =
    new AkkaEventLog[CassandraReadJournal, M](
      PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier),
      f
    )

  def jdbcEventLog[M](f: EventEnvelope => M)(implicit as: ActorSystem[Nothing]): EventLog[M] =
    new AkkaEventLog[JdbcReadJournal, M](
      PersistenceQuery(as).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier),
      f
    )
}
