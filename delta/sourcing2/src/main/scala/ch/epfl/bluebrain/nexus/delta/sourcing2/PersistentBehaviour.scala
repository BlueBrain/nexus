package ch.epfl.bluebrain.nexus.delta.sourcing2

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.sourcing2.EntityDefinition.PersistentDefinition
import ch.epfl.bluebrain.nexus.delta.sourcing2.ProcessorCommand._
import ch.epfl.bluebrain.nexus.delta.sourcing2.Response._
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.SourcingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityId
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import scala.concurrent.TimeoutException
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

/**
  * Event source based processor based on a Akka behavior which accepts and evaluates commands and then applies the
  * resulting events on the current state and finally persists them
  */
final class PersistentBehaviour(entityStore: EntityStore) {

  def apply[State, Command, Event, Rejection](
      entityId: EntityId,
      definition: PersistentDefinition[State, Command, Event, Rejection],
      config: SourcingConfig,
      stop: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand]
  )(implicit
      State: ClassTag[State],
      Command: ClassTag[Command]
  ): Behavior[ProcessorCommand] = {
    import definition._
    implicit val scheduler: Scheduler = Scheduler(config.evaluationExecutionContext)

    Behaviors.setup[ProcessorCommand] { context =>
      // Make sure that the message has been correctly routed to the appropriated actor
      def checkEntityId(onSuccess: ProcessorCommand => Behavior[ProcessorCommand]): Behavior[ProcessorCommand] =
        Behaviors.receive {
          case (_, command: Request) if command.id == entityId =>
            onSuccess(command)
          case (_, command: Request)                           =>
            context.log.warn(
              s"Unexpected message id '${command.id}' received in actor with id '$entityType/$entityId''"
            )
            Behaviors.unhandled
          case (_, command)                                    =>
            onSuccess(command)
        }

      // When evaluating Evaluate or DryRun commands, we stash incoming messages
      Behaviors.withStash(config.stashSize) { buffer =>
        def start() = {
          context.pipeToSelf(entityStore.latestState(entityType, entityId)(stateDecoder).runToFuture) {
            case Success(None)        => InitialState
            case Success(Some(value)) => RecoveredState(value)
            case Failure(th)          =>
              context.log.error(s"Error while initializing actor '$entityType/$entityId'", th)
              StartError
          }
          checkEntityId {
            case InitialState                 =>
              buffer.unstashAll(active(eventProcessor.initialState))
            case RecoveredState(State(state)) =>
              buffer.unstashAll(active(Some(state)))
            case RecoveredState(_)            =>
              Behaviors.unhandled
            case StartError                   =>
              stop(context.self)
            case req: Request                 =>
              buffer.stash(req)
              Behaviors.same
            case req: EvaluationResult        =>
              buffer.stash(req)
              Behaviors.same
            case Idle                         =>
              buffer.stash(Idle)
              Behaviors.same

          }
        }

        def newState(event: Event, state: Option[State], dryRun: Boolean): Task[EvaluationSuccess[Event, State]] = {
          val newState = eventProcessor.next(state, event)
          Task
            .unless(dryRun)(
              entityStore.save(definition)(entityType, entityId, event, newState)
            )
            .as(EvaluationSuccess(event, newState))
        }

        def evaluateCommand(state: Option[State], cmd: Command, dryRun: Boolean): Unit = {
          val scope      = if (dryRun) "testing" else "evaluating"
          val evalResult = for {
            _      <- IO.shift(config.evaluationExecutionContext)
            result <- eventProcessor
                        .evaluate(state, cmd)
                        .redeemWith(
                          rejection => UIO.pure(EvaluationRejection(rejection)),
                          event => newState(event, state, dryRun)
                        )
            _      <- IO.shift(context.executionContext)
          } yield result

          val io = evalResult
            .timeoutWith(config.evaluationMaxDuration, new TimeoutException())
            .onErrorHandleWith(err => IO.shift(context.executionContext) >> IO.raiseError(err))

          context.pipeToSelf(io.runToFuture) {
            case Success(value)               => value
            case Failure(_: TimeoutException) =>
              context.log.error(s"Timed out while $scope command '{}' on actor '{}/{}'", cmd, entityType, entityId)
              EvaluationTimeout(cmd, config.evaluationMaxDuration)
            case Failure(th)                  =>
              context.log.error(s"Error while $scope command '{}' on actor '{}/{}'", cmd, entityType, entityId)
              EvaluationFailure(cmd, Option(th.getMessage))
          }
        }

        /*
         * Initial behavior, ready to process ''Evaluate'' and ''DryRun'' messages.
         * Before evaluating a command we need the state, so we ask the stateActor for it and move our behavior to fetchingState.
         * ''RequestState'' and ''RequestLastSeqNr'' messages will be processed by forwarding them to the state actor.
         */
        def active(state: Option[State]): Behavior[ProcessorCommand] =
          checkEntityId {
            case Request.Evaluate(_, Command(subCommand), replyTo) =>
              evaluateCommand(state, subCommand, dryRun = false)
              evaluating(state, replyTo)
            case Request.DryRun(_, Command(subCommand), replyTo)   =>
              evaluateCommand(state, subCommand, dryRun = true)
              evaluating(state, replyTo)
            case Request.GetState(_, replyTo)                      =>
              replyTo ! Response.StateResponse(state)
              Behaviors.same
            case Request.Stop(_, replyTo)                          =>
              replyTo ! StopResponse
              stop(context.self)
            case Idle                                              =>
              stop(context.self)
            case Request.Evaluate(_, c, _)                         =>
              context.log.warn(
                s"Unexpected Command type during Evaluate message: '${simpleName(c)}' provided, expected '${Command.simpleName}'"
              )
              Behaviors.unhandled
            case Request.DryRun(_, c, _)                           =>
              context.log.warn(
                s"Unexpected Command type during DryRun message: '${simpleName(c)}' provided, expected '${Command.simpleName}'"
              )
              Behaviors.unhandled
            case other                                             =>
              context.log.warn(s"Unexpected message of type '${simpleName(other)}' on behavior 'active'")
              Behaviors.unhandled
          }

        def evaluating(
            state: Option[State],
            replyTo: ActorRef[Response.EvaluationResult]
        ): Behavior[ProcessorCommand] =
          checkEntityId {
            case er: EvaluationResult         =>
              replyTo ! er
              buffer.unstashAll(active(state))
            case Request.GetState(_, replyTo) =>
              replyTo ! Response.StateResponse(state)
              Behaviors.same
            case req: Request                 =>
              buffer.stash(req)
              Behaviors.same
            case Idle                         =>
              buffer.stash(Idle)
              Behaviors.same
            case _                            =>
              Behaviors.unhandled
          }

        start()
      }

    }
  }

}
