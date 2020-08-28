package ch.epfl.bluebrain.nexus.sourcingnew.processor

import java.net.URLEncoder

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.processor.AggregateReply.GetLastSeqNr
import ch.epfl.bluebrain.nexus.sourcingnew.processor.ProcessorCommand._
import ch.epfl.bluebrain.nexus.sourcingnew.{
  EventDefinition,
  PersistentEventDefinition,
  SnapshotStrategy,
  TransientEventDefinition
}
import monix.bio.IO
import monix.execution.Scheduler

import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
  * Event source based processor based on a Akka behavior which accepts and evaluates commands
  * and then applies the resulting events on the current state
  *
  * @param entityId            The id of the entity
  * @param definition          Defines the behavior to adopt for the given events
  * @param stopAfterInactivity The function to apply when the actor is being stopped,
  *                            implies passivation for sharded actors
  * @param config              The configuration
  *
  */
private[processor] class EventSourceProcessor[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
    entityId: String,
    definition: EventDefinition[State, Command, Event, Rejection],
    stopAfterInactivity: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand],
    config: AggregateConfig
) {

  protected val id = s"${definition.entityType}-${URLEncoder.encode(entityId, "UTF-8")}"

  /**
    * Behavior of the actor responsible for handling commands and events
    * @return
    */
  def behavior(): Behavior[ProcessorCommand] =
    Behaviors.setup[ProcessorCommand] { context =>
      // When evaluating Evaluate or DryRun commands, we stash incoming messages
      Behaviors.withStash(config.stashSize) { buffer =>
        // We create a child actor to apply (and maybe persist) events
        // according to the definition that has been provided
        import EventSourceProcessor._
        val behavior   = definition match {
          case p: PersistentEventDefinition[State, Command, Event, Rejection] =>
            p.stateBehavior(id, entityId, context.self)
          case t: TransientEventDefinition[State, Command, Event, Rejection]  =>
            t.stateBehavior(definition.initialState)
        }
        val stateActor = context.spawn(
          behavior,
          s"${entityId}_state"
        )

        // Make sure that the message has been correctly routed to the appropriated actor
        def onCommand(
            onSuccess: (ActorContext[ProcessorCommand], ProcessorCommand) => Behavior[ProcessorCommand]
        ): Behavior[ProcessorCommand] =
          Behaviors.receive { (context, command) =>
            command match {
              case i: InputCommand =>
                if (i.id == entityId) {
                  onSuccess(context, command)
                } else {
                  context.log.warn(s"Unexpected message id ${i.id} received in actor with id $id")
                  Behaviors.unhandled
                }
              case _               => onSuccess(context, command)
            }
          }

        // The actor is not currently evaluating anything
        def active(state: State): Behavior[ProcessorCommand] =
          onCommand { (c, cmd) =>
            cmd match {
              case ese: EventSourceCommand                                               =>
                stateActor ! ese
                Behaviors.same
              case Evaluate(_, subCommand: Command, replyTo: ActorRef[EvaluationResult]) =>
                evaluateCommand(state, subCommand, c)
                stash(state, replyTo.unsafeUpcast[RunResult])
              case DryRun(_, subCommand: Command, replyTo: ActorRef[DryRunResult])       =>
                evaluateCommand(state, subCommand, c, dryRun = true)
                stash(state, replyTo.unsafeUpcast[RunResult])
              case _: EvaluationResult                                                   =>
                context.log.error("Getting an evaluation result should happen within a stashing behavior")
                Behaviors.same
              case Idle                                                                  =>
                stopAfterInactivity(context.self)
            }
          }

        // The actor is evaluating a command so we stash commands
        // until we get a result for the current evaluation
        def stash(state: State, replyTo: ActorRef[RunResult]): Behavior[ProcessorCommand] =
          onCommand { (_, cmd) =>
            cmd match {
              case ro: ReadonlyCommand                                     =>
                stateActor ! ro
                Behaviors.same
              case success @ EvaluationSuccess(event: Event, state: State) =>
                stateActor ! Append(entityId, event)
                replyTo ! success
                active(state)
              case rejection: EvaluationRejection[Rejection]               =>
                replyTo ! rejection
                buffer.unstashAll(active(state))
              case error: EvaluationError                                  =>
                replyTo ! error
                buffer.unstashAll(active(state))
              case dryRun: DryRunResult                                    =>
                replyTo ! dryRun
                buffer.unstashAll(active(state))
              case Idle                                                    =>
                stopAfterInactivity(context.self)
              case c                                                       =>
                buffer.stash(c)
                Behaviors.same
            }
          }

        // The actor will be stopped if it doesn't receive any message
        // during the given duration
        definition.stopStrategy.lapsedSinceLastInteraction.foreach { duration =>
          context.setReceiveTimeout(duration, Idle)
        }

        active(definition.initialState)
      }
    }

  implicit private val scheduler: Scheduler = Scheduler(config.evaluationExecutionContext)

  /**
    * Runs asynchronously the given command and sends
    * the result back to the same actor which is stashing messages during this period
    * @param state the current state
    * @param cmd the command to run
    * @param context the context of the current actor
    * @param dryRun if we run as
    */
  private def evaluateCommand(
      state: State,
      cmd: Command,
      context: ActorContext[ProcessorCommand],
      dryRun: Boolean = false
  ): Unit = {
    def tellResult(evaluateResult: EvaluationResult) = {
      val result = if (dryRun) { DryRunResult(evaluateResult) }
      else { evaluateResult }
      IO(context.self ! result)
    }

    val scope = if (dryRun) "testing" else "evaluating"
    val eval  = for {
      _ <- IO.shift(config.evaluationExecutionContext)
      r <- definition.evaluate(state, cmd)
      _ <- IO.shift(context.executionContext)
      _ <- tellResult(r.map { e => EvaluationSuccess(e, definition.next(state, e)) }.valueOr(EvaluationRejection(_)))
    } yield ()
    val io    = eval
      .timeoutTo(
        config.evaluationMaxDuration, {
          context.log.error2(s"Timed out while $scope command '{}' on actor '{}'", cmd, id)
          IO.shift(context.executionContext) >> tellResult(EvaluationCommandTimeout(cmd, config.evaluationMaxDuration))
        }
      )
      .onError {
        case NonFatal(th) =>
          context.log.error2(s"Error while $scope command '{}' on actor '{}'", cmd, id)
          IO.shift(context.executionContext) >> tellResult(EvaluationCommandError(cmd, Option(th.getMessage)))
      }

    io.runAsyncAndForget
  }
}

object EventSourceProcessor {

  implicit class PersistentEventDefinitionOps[State, Command, Event: ClassTag, Rejection](
      definition: PersistentEventDefinition[State, Command, Event, Rejection]
  ) {

    import definition._

    private def commandHandler(
        actorContext: ActorContext[EventSourceCommand]
    ): (State, EventSourceCommand) => Effect[Event, State] = { (state, command) =>
      command match {
        case RequestState(_, replyTo: ActorRef[State])            =>
          Effect.reply(replyTo)(state)
        case RequestLastSeqNr(_, replyTo: ActorRef[GetLastSeqNr]) =>
          Effect.reply(replyTo)(
            GetLastSeqNr(EventSourcedBehavior.lastSequenceNumber(actorContext))
          )
        case Append(_, event: Event)                              => Effect.persist(event)
      }
    }

    def stateBehavior(
        id: String,
        entityId: String,
        parent: ActorRef[ProcessorCommand]
    ): Behavior[EventSourceCommand] = {
      import SnapshotStrategy._
      val persistenceId = PersistenceId.ofUniqueId(id)
      Behaviors.setup { context =>
        context.log.info2("Starting event source processor for type {} and id {}", entityType, entityId)
        EventSourcedBehavior[EventSourceCommand, Event, State](
          persistenceId,
          emptyState = initialState,
          commandHandler(context),
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
              context.log.error(s"Snapshots for Entity {} couldn't be deleted with target $target", failure)
            case (_, DeleteEventsCompleted(toSequenceNr: Long))                          =>
              context.log.debugN(
                "Events for Entity {} have been successfully deleted until sequence {}",
                persistenceId,
                toSequenceNr
              )
            case (_, DeleteEventsFailed(toSequenceNr: Long, failure: Throwable))         =>
              context.log.error(s"Snapshots for Entity {} couldn't be deleted until sequence $toSequenceNr", failure)
          }
      }
    }

  }

  /**
    * Event source processor relying on akka-persistence
    * so than its state can be recovered
    * @param entityId the entity identifier
    * @param definition the event definition
    * @param stopAfterInactivity the behavior to adopt when we stop the actor
    * @param config the config
    * @return
    */
  def persistent[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      entityId: String,
      definition: PersistentEventDefinition[State, Command, Event, Rejection],
      stopAfterInactivity: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand],
      config: AggregateConfig
  ): EventSourceProcessor[State, Command, Event, Rejection] =
    new EventSourceProcessor[State, Command, Event, Rejection](
      entityId,
      definition,
      stopAfterInactivity,
      config
    )

  implicit class TransientEventDefinitionOps[State, Command, Event: ClassTag, Rejection](
      definition: TransientEventDefinition[State, Command, Event, Rejection]
  ) {

    def stateBehavior(state: State): Behavior[EventSourceCommand] =
      Behaviors.receive { (_, cmd) =>
        cmd match {
          case RequestState(_, replyTo: ActorRef[State]) =>
            replyTo ! state
            Behaviors.same
          case _: RequestLastSeqNr                       =>
            Behaviors.same
          case Append(_, event: Event)                   =>
            stateBehavior(definition.next(state, event))
        }
      }
  }

  /**
    * Event source processor without persistence: if the processor is lost, so is its state
    * @param entityId the entity identifier
    * @param definition the event definition
    * @param stopAfterInactivity the behavior to adopt when we stop the actor
    * @param config the config
    * @return
    */
  def transient[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      entityId: String,
      definition: TransientEventDefinition[State, Command, Event, Rejection],
      stopAfterInactivity: ActorRef[ProcessorCommand] => Behavior[ProcessorCommand],
      config: AggregateConfig
  ) =
    new EventSourceProcessor[State, Command, Event, Rejection](
      entityId,
      definition,
      stopAfterInactivity,
      config
    )

}
