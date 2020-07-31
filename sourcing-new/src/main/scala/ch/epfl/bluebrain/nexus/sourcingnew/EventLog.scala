package ch.epfl.bluebrain.nexus.sourcingnew

import akka.persistence.query.{EventEnvelope, Offset}
import fs2._
import izumi.distage.model.definition.With

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

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
  import akka.actor.typed.ActorSystem
  import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
  import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
  import akka.persistence.query.scaladsl.ReadJournal
  import akka.persistence.query.scaladsl._
  import akka.persistence.query.{Offset, PersistenceQuery}
  import akka.stream.Materializer
  import akka.stream.scaladsl.Source
  import cats.implicits._
  import cats.effect.{Async, ContextShift}
  import streamz.converter._
  class AkkaEventLog[
    F[_]: ContextShift: Async,
    RJ <: ReadJournal with PersistenceIdsQuery
      with CurrentPersistenceIdsQuery
      with EventsByPersistenceIdQuery
      with CurrentEventsByPersistenceIdQuery
      with EventsByTagQuery
      with CurrentEventsByTagQuery,
    Event: ClassTag](readJournal: RJ)
                    (implicit as: ActorSystem[Nothing]) extends EventLog[F, Event] {

    private val Event = implicitly[ClassTag[Event]]

    implicit val executionContext: ExecutionContext = as.executionContext
    implicit val materializer: Materializer = Materializer.createMaterializer(as)

    private def toEvent(eventEnvelope: EventEnvelope) =
      eventEnvelope.event match {
        case Event(ev) => Some(ev)
        case _         => None
      }

    private def toStream[A](source: Source[A, _]) =
      source.toStream[F]( _ => ())

    override def currentPersistenceIds: Stream[F, String] = toStream(readJournal.currentPersistenceIds())

    override def persistenceIds: Stream[F, String] = toStream(readJournal.persistenceIds())

    override def eventsByPersistenceId(persistenceId: String,
                                       fromSequenceNr: Long,
                                       toSequenceNr: Long): Stream[F, Event] =
      toStream(readJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).mapFilter(toEvent)

    override def currentEventsByPersistenceId(persistenceId: String,
                                              fromSequenceNr: Long,
                                              toSequenceNr: Long): Stream[F, Event] =
      toStream(readJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)).mapFilter(toEvent)

    override def eventsByTag(tag: String,
                             offset: Offset): Stream[F, Event] =
      toStream(readJournal.eventsByTag(tag, offset)).mapFilter(toEvent)

    override def currentEventsByTag(tag: String,
                                    offset: Offset): Stream[F, Event] =
      toStream(readJournal.currentEventsByTag(tag, offset)).mapFilter(toEvent)
  }

  trait CassandraEventLogFactory[F[_]] {

    def newEventLog[Event: ClassTag]: EventLog[F, Event] @With[AkkaEventLog[F, CassandraReadJournal, Event]]
  }

  class CassandraEventLogFactoryImpl[F[_] : ContextShift : Async]
  (implicit as: ActorSystem[Nothing]) extends CassandraEventLogFactory[F] {

    override def newEventLog[Event: ClassTag]: EventLog[F, Event] =
      new AkkaEventLog[F, CassandraReadJournal, Event](
        PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
      )
  }


  trait JdbcEventLogFactory[F[_]] {

    def newEventLog[Event: ClassTag]: EventLog[F, Event] @With[AkkaEventLog[F, JdbcReadJournal, Event]]
  }

  class JdbcEventLogFactoryImpl[F[_] : ContextShift : Async]
      (implicit as: ActorSystem[Nothing]) extends JdbcEventLogFactory[F] {

    override def newEventLog[Event: ClassTag]: EventLog[F, Event] =
      new AkkaEventLog[F, JdbcReadJournal, Event](
        PersistenceQuery(as).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
      )
  }
}
