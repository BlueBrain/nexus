package ch.epfl.bluebrain.nexus.sourcing

import java.util.concurrent.ConcurrentHashMap

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.effect.syntax.all._
import cats.implicits._

/**
  * A state machine controlled through commands; successful commands result in state transitions.
  * Unsuccessful commands result in rejections returned to the caller in an __F__ context without state transitions applied.
  *
  * @tparam F          [_]       the state machine effect type
  * @tparam Identifier the type of identifier for entities
  * @tparam State      the state type
  * @tparam Command    the command type
  * @tparam Rejection  the rejection type
  */
trait StateMachine[F[_], Identifier, State, Command, Rejection] {

  /**
    * @return the name of this state machine.
    */
  def name: String

  /**
    * The current state of the entity with id __id__.
    *
    * @param id the entity identifier
    * @return the current state of the entity with id __id__
    */
  def currentState(id: Identifier): F[State]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state if the command was evaluated successfully, or the rejection of the __command__ in __F__ otherwise
    */
  def evaluate(id: Identifier, command: Command): F[Either[Rejection, State]]

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state that would be generated in __F__ if the command was tested for evaluation
    *         successfully, or the rejection of the __command__ in __F__ otherwise
    */
  def test(id: Identifier, command: Command): F[Either[Rejection, State]]

}

private[sourcing] class InMemoryStateMachine[F[_], Identifier, State, Command, Rejection](
    override val name: String,
    initialState: State,
    evaluate: (State, Command) => F[Either[Rejection, State]]
)(implicit F: Concurrent[F])
    extends StateMachine[F, Identifier, State, Command, Rejection] {

  private val map = new ConcurrentHashMap[Identifier, (Semaphore[F], State)]()

  @SuppressWarnings(Array("NullParameter"))
  private def getOrInsertDefault(id: Identifier): F[(Semaphore[F], State)] = {
    val value = map.get(id)
    if (value eq null) Semaphore[F](1).map { s =>
      val newValue = map.putIfAbsent(id, (s, initialState))
      if (newValue eq null) (s, initialState)
      else newValue
    }
    else value.pure[F]
  }

  override def evaluate(id: Identifier, command: Command): F[Either[Rejection, State]] =
    getOrInsertDefault(id).flatMap {
      case (s, _) =>
        s.acquire.bracket { _ =>
          val (_, state) = map.get(id)
          evaluate(state, command).map { either =>
            either.map { newState =>
              map.put(id, (s, newState))
              newState
            }
          }
        } { _ => s.release }
    }

  override def test(id: Identifier, command: Command): F[Either[Rejection, State]] =
    getOrInsertDefault(id).flatMap {
      case (s, _) =>
        s.acquire.bracket { _ =>
          val (_, state) = map.get(id)
          evaluate(state, command)
        } { _ => s.release }
    }

  override def currentState(id: Identifier): F[State] =
    F.delay {
      val value = map.get(id)
      if (value eq null) initialState
      else value._2
    }

}

/**
  * Factory of state machine with predefined __F[_]__ and __Identifier__ types. This is a convenience class that provides
  * a nicer developer experience by separating the effect and identifier types (that need to be explicitly specified)
  * from the state, command and rejection types which can be inferred by the compiler from the arguments.
  *
  * @tparam F          the effect type of the state machine
  * @tparam Identifier the identifier type of the state machine
  * @see [[StateMachine.inMemory]]
  */
final class StateMachineF[F[_], Identifier] {

  /**
    * Constructs an in memory state machine.
    *
    * @param name         the name of the state machine
    * @param initialState the initial state of the state machine
    * @param evaluate     command evaluation function; represented as a function that returns the evaluation in an
    *                     arbitrary effect type; may be asynchronous
    * @tparam State     the state type of the state machine
    * @tparam Command   the command type of the state machine
    * @tparam Rejection the rejection type of the state machine
    */
  def apply[Event, State, Command, Rejection](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]]
  )(implicit F: Concurrent[F]): F[StateMachine[F, Identifier, State, Command, Rejection]] =
    StateMachine.inMemoryF(name, initialState, evaluate)
}

object StateMachine {

  /**
    * Constructs a state machine factory with a fixed __F[_]__ and __Identifier__ types.
    *
    * @tparam F          the state machine evaluation effect type
    * @tparam Identifier the state machine identifier type
    */
  def inMemory[F[_], Identifier]: StateMachineF[F, Identifier] =
    new StateMachineF[F, Identifier]

  /**
    * Constructs an in memory state machine.
    *
    * @param name         the name of the state machine
    * @param initialState the initial state of the state machine
    *                     arbitrary effect type; may be asynchronous
    * @tparam F          the evaluation effect type
    * @tparam Identifier the identifier type of the state machine
    * @tparam State      the state type of the state machine
    * @tparam Command    the command type of the state machine
    * @tparam Rejection  the rejection type of the state machine
    */
  def inMemoryF[F[_], Identifier, State, Command, Rejection](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]]
  )(implicit F: Concurrent[F]): F[StateMachine[F, Identifier, State, Command, Rejection]] =
    F.delay(new InMemoryStateMachine(name, initialState, evaluate))
}
