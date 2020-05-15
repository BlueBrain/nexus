package ch.epfl.bluebrain.nexus.sourcing

import java.util.concurrent.ConcurrentHashMap

import cats.Functor
import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.effect.syntax.all._
import cats.implicits._

/**
  * A stateful event log that can be controlled through commands; successful commands result in new events appended to
  * the log and state transitions. Unsuccessful commands result in rejections returned to the caller in an __F__
  * context without any events being generated or state transitions applied.
  *
  * @tparam F[_]       the event log effect type
  * @tparam Identifier the type of identifier for entities
  * @tparam Event      the event type
  * @tparam State      the state type
  * @tparam Command    the command type
  * @tparam Rejection  the rejection type
  */
trait Aggregate[F[_], Identifier, Event, State, Command, Rejection]
    extends StatefulEventLog[F, Identifier, Event, State] {

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state and appended event in __F__ if the command was evaluated successfully, or the
    *         rejection of the __command__ in __F__ otherwise
    */
  def evaluate(id: Identifier, command: Command): F[Either[Rejection, (State, Event)]]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state in __F__ if the command was evaluated successfully, or the rejection of the
    *         __command__ in __F__ otherwise
    */
  def evaluateS(id: Identifier, command: Command)(implicit F: Functor[F]): F[Either[Rejection, State]] =
    evaluate(id, command).map(_.map { case (state, _) => state })

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly appended event in __F__ if the command was evaluated successfully, or the rejection of the
    *         __command__ in __F__ otherwise
    */
  def evaluateE(id: Identifier, command: Command)(implicit F: Functor[F]): F[Either[Rejection, Event]] =
    evaluate(id, command).map(_.map { case (_, event) => event })

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state and event that would be generated in __F__ if the command was tested for evaluation
    *         successfully, or the rejection of the __command__ in __F__ otherwise
    */
  def test(id: Identifier, command: Command): F[Either[Rejection, (State, Event)]]

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state that would be generated in __F__ if the command was tested for evaluation successfully, or the
    *         rejection of the __command__ in __F__ otherwise
    */
  def testS(id: Identifier, command: Command)(implicit F: Functor[F]): F[Either[Rejection, State]] =
    test(id, command).map(_.map { case (state, _) => state })

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the event that would be generated in __F__ if the command was tested for evaluation successfully, or the
    *         rejection of the __command__ in __F__ otherwise
    */
  def testE(id: Identifier, command: Command)(implicit F: Functor[F]): F[Either[Rejection, Event]] =
    test(id, command).map(_.map { case (_, event) => event })

}

private[sourcing] class InMemoryAggregate[F[_]: Concurrent, Identifier, Event, State, Command, Rejection](
    override val name: String,
    initialState: State,
    next: (State, Event) => State,
    evaluate: (State, Command) => F[Either[Rejection, Event]]
) extends Aggregate[F, Identifier, Event, State, Command, Rejection] {

  private val map = new ConcurrentHashMap[Identifier, (Semaphore[F], Vector[Event])]()

  @SuppressWarnings(Array("NullParameter"))
  private def getOrInsertDefault(id: Identifier): F[(Semaphore[F], Vector[Event])] = {
    val value = map.get(id)
    if (value eq null) Semaphore[F](1).map { s =>
      val newValue = map.putIfAbsent(id, (s, Vector.empty[Event]))
      if (newValue eq null) (s, Vector.empty[Event])
      else newValue
    }
    else value.pure[F]
  }

  override def evaluate(id: Identifier, command: Command): F[Either[Rejection, (State, Event)]] =
    getOrInsertDefault(id).flatMap {
      case (s, _) =>
        s.acquire.bracket { _ =>
          val (_, events) = map.get(id)
          val state       = events.foldLeft(initialState)(next)
          evaluate(state, command).map { either =>
            either.map { event =>
              map.put(id, (s, events :+ event))
              next(state, event) -> event
            }
          }
        } { _ => s.release }
    }

  override def test(id: Identifier, command: Command): F[Either[Rejection, (State, Event)]] =
    getOrInsertDefault(id).flatMap {
      case (s, _) =>
        s.acquire.bracket { _ =>
          val (_, events) = map.get(id)
          val state       = events.foldLeft(initialState)(next)
          evaluate(state, command).map { either => either.map { event => next(state, event) -> event } }
        } { _ => s.release }
    }

  override def currentState(id: Identifier): F[State] =
    foldLeft(id, initialState)(next)

  override def snapshot(id: Identifier): F[Long] =
    lastSequenceNr(id)

  override def lastSequenceNr(id: Identifier): F[Long] =
    getOrInsertDefault(id).map {
      case (_, events) => events.size.toLong
    }

  override def append(id: Identifier, event: Event): F[Long] = {
    getOrInsertDefault(id).flatMap {
      case (s, _) =>
        s.acquire.bracket { _ =>
          val (_, events) = map.compute(id, { case (_, (_, evts)) => (s, evts :+ event) })
          events.size.toLong.pure[F]
        } { _ => s.release }
    }
  }

  override def foldLeft[B](id: Identifier, z: B)(op: (B, Event) => B): F[B] =
    getOrInsertDefault(id).map {
      case (_, events) => events.foldLeft(z)(op)
    }
}

/**
  * Factory of aggregates with predefined __F[_]__ and __Identifier__ types. This is a convenience class that provides
  * a nicer developer experience by separating the effect and identifier types (that need to be explicitly specified)
  * from the event, state, command and rejection types which can be inferred by the compiler from the arguments.
  *
  * @tparam F          the effect type of the aggregate
  * @tparam Identifier the identifier type of the aggregate
  * @see [[Aggregate.inMemory]]
  */
final class AggregateF[F[_], Identifier] {

  /**
    * Constructs an in memory aggregate.
    *
    * @param name         the name of the aggregate
    * @param initialState the initial state of the aggregate
    * @param next         state transition function; represented as a total function without any effect types; state
    *                     transition functions should be pure
    * @param evaluate     command evaluation function; represented as a function that returns the evaluation in an
    *                     arbitrary effect type; may be asynchronous
    * @tparam Event       the event type of the aggregate
    * @tparam State       the state type of the aggregate
    * @tparam Command     the command type of the aggregate
    * @tparam Rejection   the rejection type of the aggregate
    */
  def apply[Event, State, Command, Rejection](
      name: String,
      initialState: State,
      next: (State, Event) => State,
      evaluate: (State, Command) => F[Either[Rejection, Event]]
  )(implicit F: Concurrent[F]): F[Aggregate[F, Identifier, Event, State, Command, Rejection]] =
    Aggregate.inMemoryF(name, initialState, next, evaluate)

}

object Aggregate {

  /**
    * Constructs an aggregate factory with a fixed __F[_]__ and __Identifier__ types.
    *
    * @tparam F          the aggregate evaluation effect type
    * @tparam Identifier the aggregate identifier type
    */
  def inMemory[F[_], Identifier]: AggregateF[F, Identifier] =
    new AggregateF[F, Identifier]

  /**
    * Constructs an in memory aggregate.
    *
    * @param name         the name of the aggregate
    * @param initialState the initial state of the aggregate
    * @param next         state transition function; represented as a total function without any effect types; state
    *                     transition functions should be pure
    * @param evaluate     command evaluation function; represented as a function that returns the evaluation in an
    *                     arbitrary effect type; may be asynchronous
    * @tparam F           the evaluation effect type
    * @tparam Identifier  the identifier type of the aggregate
    * @tparam Event       the event type of the aggregate
    * @tparam State       the state type of the aggregate
    * @tparam Command     the command type of the aggregate
    * @tparam Rejection   the rejection type of the aggregate
    */
  def inMemoryF[F[_]: Concurrent, Identifier, Event, State, Command, Rejection](
      name: String,
      initialState: State,
      next: (State, Event) => State,
      evaluate: (State, Command) => F[Either[Rejection, Event]]
  ): F[Aggregate[F, Identifier, Event, State, Command, Rejection]] =
    implicitly[Concurrent[F]].delay {
      new InMemoryAggregate(name, initialState, next, evaluate)
    }
}
