package ch.epfl.bluebrain.nexus.sourcingnew

import akka.persistence.query.{EventEnvelope, Offset}
import fs2._
import scala.concurrent.ExecutionContext

trait EventLog[F[_], Event] {
  def currentPersistenceIds: Stream[F, String]
  def persistenceIds: Stream[F, String]
  def eventsByPersistenceId(persistenceId: String,
                            fromSequenceNr: Long,
                            toSequenceNr: Long): Stream[F, Event]
  def currentEventsByPersistenceId(persistenceId: String,
                                   fromSequenceNr: Long,
                                   toSequenceNr: Long): Stream[F, Event]
  def eventsByTag(tag: String,
                  offset: Offset): Stream[F, Event]
  def currentEventsByTag(tag: String,
                         offset: Offset):Stream[F, Event]
}
object EventLog {
  import akka.actor.ActorSystem
  import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
  import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
  import akka.persistence.query.scaladsl.ReadJournal
  import akka.persistence.query.scaladsl._
  import akka.persistence.query.{Offset, PersistenceQuery}
  import akka.stream.Materializer
  import akka.stream.scaladsl.Source
  import cats.effect.{Async, ContextShift}
  import streamz.converter._
  private class AkkaEventLog[
    F[_]: ContextShift: Async,
    RJ <: ReadJournal with PersistenceIdsQuery
      with CurrentPersistenceIdsQuery
      with EventsByPersistenceIdQuery
      with CurrentEventsByPersistenceIdQuery
      with EventsByTagQuery
      with CurrentEventsByTagQuery,
    Event](readJournal: RJ,
           f: EventEnvelope => Event)
          (implicit as: ActorSystem) extends EventLog[F, Event] {
    implicit val executionContext: ExecutionContext = as.dispatcher
    implicit val materializer: Materializer = Materializer.createMaterializer(as)
    private def toStream[A](source: Source[A, _]) =
      source.toStream[F]( _ => ())
    override def currentPersistenceIds: Stream[F, String] = toStream(readJournal.currentPersistenceIds())
    override def persistenceIds: Stream[F, String] = toStream(readJournal.persistenceIds())
    override def eventsByPersistenceId(persistenceId: String,
                                       fromSequenceNr: Long,
                                       toSequenceNr: Long): Stream[F, Event] =
      toStream(readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).map(f)
    override def currentEventsByPersistenceId(persistenceId: String,
                                              fromSequenceNr: Long,
                                              toSequenceNr: Long): Stream[F, Event] =
      toStream(readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).map(f)
    override def eventsByTag(tag: String,
                             offset: Offset): Stream[F, Event] =
      toStream(readJournal.eventsByTag(tag, offset)).map(f)
    override def currentEventsByTag(tag: String,
                                    offset: Offset): Stream[F, Event] =
      toStream(readJournal.currentEventsByTag(tag, offset)).map(f)
  }
  def cassandra[F[_]: ContextShift: Async, Event](f: EventEnvelope => Event)
                                                 (implicit as: ActorSystem): EventLog[F, Event] =
    new AkkaEventLog[F, CassandraReadJournal, Event](
      PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier), f
    )
  def jdbc[F[_]: ContextShift: Async, Event](f: EventEnvelope => Event)
                                            (implicit as: ActorSystem): EventLog[F, Event] =
    new AkkaEventLog[F, JdbcReadJournal, Event](
      PersistenceQuery(as).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier), f
    )
}
