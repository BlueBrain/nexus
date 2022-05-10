package ch.epfl.bluebrain.nexus.delta.sourcing.processor

import akka.actor.typed.scaladsl.{Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria, SnapshotCountRetentionCriteria}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.SnapshotStrategy.{NoSnapshot, SnapshotEvery}
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.StopResponse
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.EventSourceProcessor._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ProcessorCommand._
import ch.epfl.bluebrain.nexus.delta.sourcing.{EventDefinition, PersistentEventDefinition, SnapshotStrategy, TransientEventDefinition}
import com.datastax.oss.driver.api.core.DriverTimeoutException
import monix.bio.IO
import monix.execution.Scheduler

import scala.concurrent.TimeoutException
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

/**
  * Event source based processor based on a Akka behavior which accepts and evaluates commands and then applies the
  * resulting events on the current state
  *
  * @param entityId
  *   The id of the entity
  * @param definition
  *   Defines the behavior to adopt for the given events
  * @param stopAfterInactivity
  *   The function to apply when the actor is being stopped, implies passivation for sharded actors
  * @param config
  *   The configuration
  */
private[processor] class EventSourceProcessor[State, Command, Event, Rejection](
    entityId: String,
    definition: EventDefinition[State, Command, Event, Rejection],
    stopAfterInactivity: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand],
    config: EventSourceProcessorConfig
)(implicit State: ClassTag[State], Command: ClassTag[Command], Event: ClassTag[Event], Rejection: ClassTag[Rejection]) {

  protected val id: String = persistenceId(definition.entityType, entityId)

  /**
    * The behavior of the underlying state actor when we opted for persisting the events
    */
  private def persistentBehavior(
      persistentEventDefinition: PersistentEventDefinition[State, Command, Event, Rejection],
      parent: ActorRef[ProcessorCommand]
  ) = {
    import persistentEventDefinition._
    val persistenceId = PersistenceId.ofUniqueId(id)

    Behaviors.setup[ChildActorRequest] { context =>
      context.log.debug2("Starting event source processor for type '{}' and id '{}'", entityType, entityId)
      EventSourcedBehavior[ChildActorRequest, Event, State](
        persistenceId,
        emptyState = initialState,
        // Command handler
        { (state, command) =>
          command match {
            case ChildActorRequest.RequestState(replyTo)     =>
              Effect.reply(replyTo)(AggregateResponse.StateResponse(state))
            case ChildActorRequest.RequestLastSeqNr(replyTo) =>
              Effect.reply(replyTo)(AggregateResponse.LastSeqNr(EventSourcedBehavior.lastSequenceNumber(context)))
            case ChildActorRequest.RequestStateInternal      =>
              Effect.reply(parent)(ChildActorResponse.StateResponseInternal(state))
            case ChildActorRequest.Append(Event(event))      =>
              Effect.persist(event).thenReply(parent)(state => ChildActorResponse.AppendResult(event, state))
            case ChildActorRequest.Append(e)                 =>
              context.log.warn(
                s"Unexpected Event type during Append message: '${simpleName(e)}' provided, expected '${Event.simpleName}'"
              )
              Effect.none
          }
        },
        // Event handler
        next
      ).withTagger(tagger)
        .snapshotStrategy(snapshotStrategy)
        .receiveSignal {
          case (state, RecoveryCompleted)                                              =>
            // The actor will be stopped/passivated after a fix period after recovery
            stopStrategy.lapsedSinceRecoveryCompleted.foreach { duration =>
              context.scheduleOnce(duration, parent, Idle)
            }
            context.log.debugN("Entity {} has been successfully recovered at state {}", persistenceId, state)
          case (state, RecoveryFailed(failure))                                        =>
            context.log.error(s"Entity $persistenceId couldn't be recovered at state $state", failure)
          case (state, SnapshotCompleted(metadata: SnapshotMetadata))                  =>
            context.log.debugN(
              "Entity {} has been successfully snapshotted at state {} with metadata {}",
              persistenceId,
              state,
              metadata
            )
          case (state, SnapshotFailed(metadata: SnapshotMetadata, failure: Throwable)) =>
            context.log.error(
              s"Entity $persistenceId couldn't be snapshotted at state $state with metadata $metadata",
              failure
            )
          case (_, DeleteSnapshotsCompleted(target: DeletionTarget))                   =>
            context.log.debugN(
              "Snapshots for Entity {} have been successfully deleted with target {}",
              persistenceId,
              target
            )
          case (_, DeleteSnapshotsFailed(target: DeletionTarget, failure: Throwable))  =>
            context.log.error(s"Snapshots for Entity $persistenceId couldn't be deleted with target $target", failure)
          case (_, DeleteEventsCompleted(toSequenceNr: Long))                          =>
            context.log.debugN(
              "Events for Entity {} have been successfully deleted until sequence {}",
              persistenceId,
              toSequenceNr
            )
          case (_, DeleteEventsFailed(toSequenceNr: Long, failure: Throwable))         =>
            context.log.error(
              s"Snapshots for Entity $persistenceId couldn't be deleted until sequence $toSequenceNr",
              failure
            )
          case (_, PostStop)                                                           =>
            context.log.debug(s"Stopped actor for persistence id {}", persistenceId)
        }
    }
  }

  /**
    * The behavior of the underlying state actor when we opted for NOT persisting the events
    */
  private def transientBehavior(
      t: TransientEventDefinition[State, Command, Event, Rejection],
      parent: ActorRef[ProcessorCommand]
  ): Behavior[ChildActorRequest] = {
    def behavior(state: State): Behaviors.Receive[ChildActorRequest] =
      Behaviors.receive[ChildActorRequest] { (ctx, cmd) =>
        cmd match {
          case ChildActorRequest.RequestState(replyTo) =>
            replyTo ! AggregateResponse.StateResponse(state)
            Behaviors.same
          case ChildActorRequest.RequestLastSeqNr(_)   =>
            Behaviors.same
          case ChildActorRequest.RequestStateInternal  =>
            parent ! ChildActorResponse.StateResponseInternal(state)
            Behaviors.same
          case ChildActorRequest.Append(Event(event))  =>
            val newState = t.next(state, event)
            parent ! ChildActorResponse.AppendResult(event, newState)
            behavior(newState)
          case ChildActorRequest.Append(e)             =>
            ctx.log.warn(
              s"Unexpected Event type during Append message: '${simpleName(e)}' provided, expected '${Event.simpleName}'"
            )
            Behaviors.same

        }
      }
    behavior(t.initialState)
  }

  /**
    * Behavior of the actor responsible for handling commands and events. There are multiple behaviors, each of them
    * responsible for a different stage of a command evaluation.
    *
    * The following is a diagram of the different behaviors and their order:
    *
    * ┌--------┐ ┌---------------┐ ┌------------┐ ┌-----------┐ │ │ │ │ │ │ !dryRun │ │ │ active +-------->
    * fetchingState │--------> evaluating │---------> appending │---┐ │ │ │ │ │ │---┐ │ │ │ └---^----┘ └---------------┘
    * └------------┘ │ └-----------┘ │ │ │ │
    * └-------------------------------------------------------┴---------------------┘ dryRun
    */
  def behavior(): Behavior[ProcessorCommand] =
    Behaviors.setup[ProcessorCommand] { context =>
      // When evaluating Evaluate or DryRun commands, we stash incoming messages
      Behaviors.withStash(config.stashSize) { buffer =>
        // We create a child actor to apply (and maybe persist) events
        // according to the definition that has been provided
        val behavior = definition match {
          case p: PersistentEventDefinition[State, Command, Event, Rejection] =>
            persistentBehavior(p, context.self)
          case t: TransientEventDefinition[State, Command, Event, Rejection]  =>
            transientBehavior(t, context.self)
        }

        val stateActor = context.spawn(behavior, s"${UrlUtils.encode(entityId)}_state")

        // Make sure that the message has been correctly routed to the appropriated actor
        def checkEntityId(onSuccess: ProcessorCommand => Behavior[ProcessorCommand]): Behavior[ProcessorCommand] =
          Behaviors.receive {
            case (_, command: AggregateRequest) if command.id == entityId =>
              onSuccess(command)
            case (_, command: AggregateRequest)                           =>
              context.log.warn(s"Unexpected message id '${command.id}' received in actor with id '$id''")
              Behaviors.unhandled
            case (_, command)                                             =>
              onSuccess(command)
          }

        def toChildActorRequest(readOnly: AggregateRequest.ReadOnlyRequest): ChildActorRequest =
          readOnly match {
            case AggregateRequest.RequestState(_, replyTo)     => ChildActorRequest.RequestState(replyTo)
            case AggregateRequest.RequestLastSeqNr(_, replyTo) => ChildActorRequest.RequestLastSeqNr(replyTo)
          }

        def toAggregateResponse(result: EvaluationResultInternal): Option[AggregateResponse.EvaluationResult] =
          result match {
            case EvaluationResultInternal.EvaluationSuccess(Event(event), State(state)) =>
              Some(AggregateResponse.EvaluationSuccess(event, state))
            case EvaluationResultInternal.EvaluationRejection(Rejection(rej))           =>
              Some(AggregateResponse.EvaluationRejection(rej))
            case EvaluationResultInternal.EvaluationTimeout(Command(cmd), timeoutAfter) =>
              Some(AggregateResponse.EvaluationTimeout(cmd, timeoutAfter))
            case EvaluationResultInternal.EvaluationFailure(Command(cmd), message)      =>
              Some(AggregateResponse.EvaluationFailure(cmd, message))
            case EvaluationResultInternal.EvaluationSuccess(e, s)                       =>
              context.log.warn(
                s"Unexpected Event/State type during EvaluationSuccess message: " +
                  s"event '${simpleName(e)}' provided, expected event '${Event.simpleName}', state '${simpleName(s)}' provided, expected state '${State.simpleName}'"
              )
              None
            case EvaluationResultInternal.EvaluationRejection(r)                        =>
              context.log.warn(
                s"Unexpected Rejection type during EvaluationRejection message: '${simpleName(r)}' provided, expected '${Rejection.simpleName}'"
              )
              None
            case EvaluationResultInternal.EvaluationFailure(c, _)                       =>
              context.log.warn(
                s"Unexpected Command type during EvaluationFailure message: '${simpleName(c)}' provided, expected '${Command.simpleName}'"
              )
              None
            case EvaluationResultInternal.EvaluationTimeout(c, _)                       =>
              context.log.warn(
                s"Unexpected Command type during EvaluationTimeout message: '${simpleName(c)}' provided, expected '${Command.simpleName}'"
              )
              None
          }

        // Evaluates the command and sends a message to self with the evaluation result
        def evaluateCommand(state: State, cmd: Command, dryRun: Boolean): Unit = {
          val scope      = if (dryRun) "testing" else "evaluating"
          val evalResult = for {
            _ <- IO.shift(config.evaluationExecutionContext)
            r <- definition.evaluate(state, cmd).attempt
            _ <- IO.shift(context.executionContext)
          } yield r
            .map(e => EvaluationResultInternal.EvaluationSuccess(e, definition.next(state, e)))
            .valueOr(EvaluationResultInternal.EvaluationRejection(_))

          val io = evalResult
            .timeoutWith(config.evaluationMaxDuration, new TimeoutException())
            .onErrorHandleWith(err => IO.shift(context.executionContext) >> IO.raiseError(err))

          context.pipeToSelf(io.runToFuture) {
            case Success(value)                                           => value
            case Failure(_: TimeoutException | _: DriverTimeoutException) =>
              context.log.error2(s"Timed out while $scope command '{}' on actor '{}'", cmd, id)
              EvaluationResultInternal.EvaluationTimeout(cmd, config.evaluationMaxDuration)
            case Failure(th)                                              =>
              context.log.error2(s"Error while $scope command '{}' on actor '{}'", cmd, id)
              EvaluationResultInternal.EvaluationFailure(cmd, Option(th.getMessage))
          }
        }

        /*
         * Initial behavior, ready to process ''Evaluate'' and ''DryRun'' messages.
         * Before evaluating a command we need the state, so we ask the stateActor for it and move our behavior to fetchingState.
         * ''RequestState'' and ''RequestLastSeqNr'' messages will be processed by forwarding them to the state actor.
         */
        def active(): Behavior[ProcessorCommand] =
          checkEntityId {
            case AggregateRequest.Evaluate(_, Command(subCommand), replyTo) =>
              stateActor ! ChildActorRequest.RequestStateInternal
              fetchingState(subCommand, replyTo, dryRun = false)
            case AggregateRequest.DryRun(_, Command(subCommand), replyTo)   =>
              stateActor ! ChildActorRequest.RequestStateInternal
              fetchingState(subCommand, replyTo, dryRun = true)
            case readOnly: AggregateRequest.ReadOnlyRequest                 =>
              stateActor ! toChildActorRequest(readOnly)
              Behaviors.same
            case AggregateRequest.RequestStop(_, replyTo)                   =>
              replyTo ! StopResponse
              stopAfterInactivity(context.self)
            case Idle                                                       =>
              stopAfterInactivity(context.self)
            case ChildActorResponse.AppendResult(_, _)                      =>
              context.log.error("Getting an append result should happen within the 'appending' behavior")
              Behaviors.unhandled
            case ChildActorResponse.StateResponseInternal(_)                =>
              context.log.error("Getting the state from within should happen within the 'fetchingState' behavior")
              Behaviors.unhandled
            case AggregateRequest.Evaluate(_, c, _)                         =>
              context.log.warn(
                s"Unexpected Command type during Evaluate message: '${simpleName(c)}' provided, expected '${Command.simpleName}'"
              )
              Behaviors.unhandled
            case AggregateRequest.DryRun(_, c, _)                           =>
              context.log.warn(
                s"Unexpected Command type during DryRun message: '${simpleName(c)}' provided, expected '${Command.simpleName}'"
              )
              Behaviors.unhandled
            case other                                                      =>
              context.log.warn(s"Unexpected message of type '${simpleName(other)}' on behavior 'active'")
              Behaviors.unhandled

          }

        /*
         * The second behavior, ready to process ''StateResponseInternal'' messages.
         * Once received, we trigger command Evaluation/DryRun and move our behavior to evaluating.
         * ''RequestState'' and ''RequestLastSeqNr'' messages will be processed by forwarding them to the state actor,
         * while any other ''Evaluate'' or ''DryRun'' will be stashed.
         */
        def fetchingState(
            subCommand: Command,
            replyTo: ActorRef[AggregateResponse.EvaluationResult],
            dryRun: Boolean
        ): Behavior[ProcessorCommand] =
          checkEntityId {
            case ChildActorResponse.StateResponseInternal(State(state)) =>
              evaluateCommand(state, subCommand, dryRun)
              evaluating(replyTo, dryRun)
            case readOnly: AggregateRequest.ReadOnlyRequest             =>
              stateActor ! toChildActorRequest(readOnly)
              Behaviors.same
            case AggregateRequest.RequestStop(_, replyTo)               =>
              replyTo ! StopResponse
              stopAfterInactivity(context.self)
            case Idle                                                   =>
              stopAfterInactivity(context.self)
            case req: AggregateRequest                                  =>
              buffer.stash(req)
              Behaviors.same
            case _: EvaluationResultInternal                            =>
              context.log.error("Getting an evaluation result should happen within the 'evaluating' behavior")
              Behaviors.unhandled
            case ChildActorResponse.AppendResult(_, _)                  =>
              context.log.error("Getting an append result should happen within the 'appending' behavior")
              Behaviors.unhandled
            case ChildActorResponse.StateResponseInternal(s)            =>
              context.log.warn(
                s"Unexpected State type during StateResponseInternal message: '${simpleName(s)}' provided, expected '${State.simpleName}'"
              )
              Behaviors.unhandled
            case other                                                  =>
              context.log.warn(s"Unexpected message of type '${simpleName(other)}' on behavior 'fetchingState'")
              Behaviors.unhandled
          }

        /*
         * The third behavior, ready to process evaluation results ''EvaluationResult'' messages.
         * If the evaluation succeeded and we are not on a ''DryRun'', we will send an ''Append'' message to the stateActor
         * with the computed event and move to the appending behavior.
         * Otherwise we return the ''EvaluationResult'' to the client and unstash other messages while moving to active behavior.
         * ''RequestState'' and ''RequestLastSeqNr'' messages will be processed by forwarding them to the state actor,
         * while any other ''Evaluate'' or ''DryRun'' will be stashed.
         */
        def evaluating(
            replyTo: ActorRef[AggregateResponse.EvaluationResult],
            dryRun: Boolean
        ): Behavior[ProcessorCommand] =
          checkEntityId {
            case EvaluationResultInternal.EvaluationSuccess(Event(event), _) if !dryRun =>
              stateActor ! ChildActorRequest.Append(event)
              appending(replyTo)
            case r: EvaluationResultInternal                                            =>
              toAggregateResponse(r).foreach(replyTo ! _)
              buffer.unstashAll(active())
            case readOnly: AggregateRequest.ReadOnlyRequest                             =>
              stateActor ! toChildActorRequest(readOnly)
              Behaviors.same
            case req: AggregateRequest                                                  =>
              buffer.stash(req)
              Behaviors.same
            case Idle                                                                   =>
              buffer.stash(Idle)
              Behaviors.same
            case ChildActorResponse.AppendResult(_, _)                                  =>
              context.log.error("Getting an append result should happen within the 'appending' behavior")
              Behaviors.unhandled
            case ChildActorResponse.StateResponseInternal(_)                            =>
              context.log.error("Getting the state from within should happen within the 'fetchingState' behavior")
              Behaviors.unhandled
          }

        /*
         * The fourth behavior, ready to process ''AppendResult'' messages, which confirm correct append on the event log from the stateActor.
         * After that we reply to the client and unstash other messages while moving to active behavior.
         * ''RequestState'' and ''RequestLastSeqNr'' messages will be processed by forwarding them to the state actor,
         * while any other ''Evaluate'' or ''DryRun'' will be stashed.
         */
        def appending(replyTo: ActorRef[AggregateResponse.EvaluationResult]): Behavior[ProcessorCommand] =
          checkEntityId {
            case ChildActorResponse.AppendResult(Event(event), State(state)) =>
              replyTo ! AggregateResponse.EvaluationSuccess(event, state)
              buffer.unstashAll(active())
            case readOnly: AggregateRequest.ReadOnlyRequest                  =>
              stateActor ! toChildActorRequest(readOnly)
              Behaviors.same
            case req: AggregateRequest                                       =>
              buffer.stash(req)
              Behaviors.same
            case Idle                                                        =>
              buffer.stash(Idle)
              Behaviors.same
            case _: EvaluationResultInternal                                 =>
              context.log.error("Getting an evaluation result should happen within the 'evaluating' behavior")
              Behaviors.unhandled
            case ChildActorResponse.StateResponseInternal(_)                 =>
              context.log.error("Getting the state from within should happen within the 'fetchingState' behavior")
              Behaviors.unhandled
            case ChildActorResponse.AppendResult(e, s)                       =>
              context.log.warn(
                s"Unexpected Event/State type during AppendResult message: event '${simpleName(
                  e
                )}' provided, expected event '${Event.simpleName}', state '${simpleName(s)}' provided, expected state '${State.simpleName}'"
              )
              Behaviors.unhandled
          }

        // The actor will be stopped if it doesn't receive any message
        // during the given duration
        definition.stopStrategy.lapsedSinceLastInteraction.foreach { duration =>
          context.setReceiveTimeout(duration, Idle)
        }

        active()
      }
    }

  implicit private val scheduler: Scheduler = Scheduler(config.evaluationExecutionContext)
}

object EventSourceProcessor {

  /**
    * Create persistence id for an entity.
    *
    * @param entityType
    *   entity type
    * @param entityId
    *   entity id
    *
    * @return
    *   persistence ID for the entity
    */
  def persistenceId(entityType: String, entityId: String): String =
    s"$entityType-${UrlUtils.encode(entityId)}"

  /**
    * To add our Snapshot strategy to an EventSourcedBehavior in a more concise way
    */
  implicit private[processor] class EventSourcedBehaviorOps[C, E, State](
      val eventSourcedBehavior: EventSourcedBehavior[C, E, State]
  ) extends AnyVal {

    private def toSnapshotCriteria(snapshotEvery: SnapshotEvery): SnapshotCountRetentionCriteria = {
      val criteria = RetentionCriteria.snapshotEvery(snapshotEvery.numberOfEvents, snapshotEvery.keepNSnapshots)
      if (snapshotEvery.deleteEventsOnSnapshot)
        criteria.withDeleteEventsOnSnapshot
      else
        criteria
    }

    def snapshotStrategy(strategy: SnapshotStrategy): EventSourcedBehavior[C, E, State] =
      strategy match {
        case NoSnapshot       => eventSourcedBehavior
        case s: SnapshotEvery =>
          eventSourcedBehavior.withRetention(toSnapshotCriteria(s))
      }
  }

  /**
    * Event source processor relying on akka-persistence so than its state can be recovered
    *
    * @param entityId
    *   the entity identifier
    * @param definition
    *   the event definition
    * @param stopAfterInactivity
    *   the behavior to adopt when we stop the actor
    * @param config
    *   the config
    */
  def persistent[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      entityId: String,
      definition: PersistentEventDefinition[State, Command, Event, Rejection],
      stopAfterInactivity: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand],
      config: EventSourceProcessorConfig
  ): EventSourceProcessor[State, Command, Event, Rejection] =
    new EventSourceProcessor[State, Command, Event, Rejection](
      entityId,
      definition,
      stopAfterInactivity,
      config
    )

  /**
    * Event source processor without persistence: if the processor is lost, so is its state
    *
    * @param entityId
    *   the entity identifier
    * @param definition
    *   the event definition
    * @param stopAfterInactivity
    *   the behavior to adopt when we stop the actor
    * @param config
    *   the config
    */
  def transient[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      entityId: String,
      definition: TransientEventDefinition[State, Command, Event, Rejection],
      stopAfterInactivity: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand],
      config: EventSourceProcessorConfig
  ) =
    new EventSourceProcessor[State, Command, Event, Rejection](
      entityId,
      definition,
      stopAfterInactivity,
      config
    )

}
