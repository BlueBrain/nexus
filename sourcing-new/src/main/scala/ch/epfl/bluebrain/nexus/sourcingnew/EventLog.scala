package ch.epfl.bluebrain.nexus.sourcingnew

import akka.persistence.query.{EventEnvelope, Offset}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.Message
import fs2._
import izumi.distage.model.definition.With

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

/**
  * A log of ordered events for uniquely identifiable entities.
  *
  * @tparam F[_]       the event log effect type
  * @tparam M      the event type
  */
trait EventLog[F[_], M] {
  def currentPersistenceIds: Stream[F, String]
  def persistenceIds: Stream[F, String]
  def eventsByPersistenceId(persistenceId: String,
                            fromSequenceNr: Long,
                            toSequenceNr: Long): Stream[F, M]
  def currentEventsByPersistenceId(persistenceId: String,
                                   fromSequenceNr: Long,
                                   toSequenceNr: Long): Stream[F, M]
  def eventsByTag(tag: String,
                  offset: Offset): Stream[F, M]
  def currentEventsByTag(tag: String,
                         offset: Offset):Stream[F, M]
}
object EventLog {
  import akka.actor.typed.ActorSystem
  import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
  import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
  import akka.persistence.query.scaladsl.{ReadJournal, _}
  import akka.persistence.query.{Offset, PersistenceQuery}
  import akka.stream.Materializer
  import akka.stream.scaladsl.Source
  import cats.effect.{Async, ContextShift}
  import streamz.converter._

  /**
    * Implementation of [[EventLog]] based on Akka Persistence
    * @param readJournal the underlying akka persistence journal
    * @param f the function to transform envelopes
    * @param as the actor system
    * @tparam F[_]       the event log effect type
    * @tparam RJ         the underlying journal typr (Cassandra / JDBC / ...)
    * @tparam M      the event type
    */
  class AkkaEventLog[
    F[_]: ContextShift: Async,
    RJ <: ReadJournal with PersistenceIdsQuery
      with CurrentPersistenceIdsQuery
      with EventsByPersistenceIdQuery
      with CurrentEventsByPersistenceIdQuery
      with EventsByTagQuery
      with CurrentEventsByTagQuery,
    M](readJournal: RJ, f: EventEnvelope => M)
                (implicit as: ActorSystem[Nothing]) extends EventLog[F, M] {

    implicit val executionContext: ExecutionContext = as.executionContext
    implicit val materializer: Materializer = Materializer.createMaterializer(as)

    private def toStream[A](source: Source[A, _]) =
      source.toStream[F]( _ => ())

    override def currentPersistenceIds: Stream[F, String] = toStream(readJournal.currentPersistenceIds())

    override def persistenceIds: Stream[F, String] = toStream(readJournal.persistenceIds())

    override def eventsByPersistenceId(persistenceId: String,
                                       fromSequenceNr: Long,
                                       toSequenceNr: Long): Stream[F, M] =
      toStream(readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).map(f)

    override def currentEventsByPersistenceId(persistenceId: String,
                                              fromSequenceNr: Long,
                                              toSequenceNr: Long): Stream[F, M] =
      toStream(readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).map(f)

    override def eventsByTag(tag: String,
                             offset: Offset): Stream[F, M] =
      toStream(readJournal.eventsByTag(tag, offset)).map(f)

    override def currentEventsByTag(tag: String,
                                    offset: Offset): Stream[F, M] =
      toStream(readJournal.currentEventsByTag(tag, offset)).map(f)
  }

  trait CassandraEventLogFactory[F[_]] {

    def newEventLog[M](f: EventEnvelope => M): EventLog[F, M] @With[AkkaEventLog[F, CassandraReadJournal, M]]

    def newProjectionEventLog[A: ClassTag]: EventLog[F, Message[A]] =
      newEventLog(Message.apply)
  }

  class CassandraEventLogFactoryImpl[F[_] : ContextShift : Async]
  (implicit as: ActorSystem[Nothing]) extends CassandraEventLogFactory[F] {

    override def newEventLog[M](f: EventEnvelope => M): EventLog[F, M] =
      new AkkaEventLog[F, CassandraReadJournal, M](
        PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier),
        f
      )
  }


  trait JdbcEventLogFactory[F[_]] {

    def newEventLog[M](f: EventEnvelope => M): EventLog[F, M] @With[AkkaEventLog[F, JdbcReadJournal, M]]
  }

  class JdbcEventLogFactoryImpl[F[_] : ContextShift : Async]
      (implicit as: ActorSystem[Nothing]) extends JdbcEventLogFactory[F] {

    override def newEventLog[M](f: EventEnvelope => M): EventLog[F, M] =
      new AkkaEventLog[F, JdbcReadJournal, M](
        PersistenceQuery(as).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier),
        f
      )
  }
}
