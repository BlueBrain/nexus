package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.{EveryNode, PersistentSingleNode, TransientSingleNode}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task, UIO}
import retry.syntax.all._

/**
  * Supervises the execution of projections based on a defined [[ExecutionStrategy]] that describes whether projections
  * should be executed on all the nodes or a single node and whether offsets should be persisted.
  *
  * It monitors and restarts automatically projections that have stopped or failed.
  *
  * Projections that completed naturally are not restarted or cleaned up such that the status can be read.
  *
  * When the supervisor is stopped, all running projections are also stopped.
  */
trait Supervisor {

  /**
    * Supervises the execution of the provided `projection`.
    * A second call to this method with a projection with the same name will cause the current projection to be stopped and replaced by
    * the new one.
    * @param projection
    *   the projection to supervise
    * @see
    *   [[Supervisor]]
    */
  def run(projection: CompiledProjection): Task[ExecutionStatus] =
    run(projection)

  /**
    * Stops the projection with the provided `name` and removes it from supervision. It performs a noop if the
    * projection does not exist or it is not running on the current node. It executes the provided finalizer after the
    * projection is stopped.
    * @param name
    *   the name of the projection
    */
  def destroy(name: String): Task[Option[ExecutionStatus]]

  /**
    * Returns the status of the projection with the provided `name`, if a projection with such name exists.
    * @param name
    *   the name of the projection
    */
  def describe(name: String): Task[Option[SupervisedDescription]]

  /**
    * Stops all running projections without removing them from supervision.
    */
  def stop(): Task[Unit]

}

object Supervisor {

  private val log: Logger = Logger[Supervisor]

  private val ignored = Control(
    status = Task.pure(ExecutionStatus.Ignored),
    progress = Task.pure(ProjectionProgress.NoProgress),
    stop = Task.unit
  )

  /**
    * Constructs a new [[Supervisor]] instance using the provided `store` and `cfg`.
    * @param store
    *   the projection store for handling offsets
    * @param cfg
    *   the projection configuration
    */
  def apply(store: ProjectionStore, cfg: ProjectionConfig): Task[Supervisor] =
    for {
      _              <- Task.delay(log.info("Starting Delta supervisor"))
      semaphore      <- Semaphore[Task](1L)
      mapRef         <- Ref.of[Task, Map[String, Supervised]](Map.empty)
      signal         <- SignallingRef[Task, Boolean](false)
      supervision    <- supervisionTask(semaphore, mapRef, signal, cfg).start
      supervisionRef <- Ref.of[Task, Fiber[Throwable, Unit]](supervision)
      _              <- Task.delay(log.info("Delta supervisor is up"))
    } yield new Impl(store, cfg, semaphore, mapRef, signal, supervisionRef)

  private def supervisionTask(
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
      signal: SignallingRef[Task, Boolean],
      cfg: ProjectionConfig
  ): Task[Unit] = {
    Stream
      .awakeEvery[Task](cfg.supervisionCheckInterval)
      .evalTap( _ => Task.delay(log.debug("Checking projection statuses")))
      .evalMap(_ => mapRef.get)
      .flatMap(map => Stream.iterable(map.values))
      .evalMap { supervised =>
        val metadata = supervised.metadata
        supervised.control.status.flatMap {
          case ExecutionStatus.Ignored                             => Task.unit
          case ExecutionStatus.Pending                             => Task.unit
          case ExecutionStatus.Running                             => Task.unit
          case ExecutionStatus.Passivated                          => Task.unit
          case ExecutionStatus.Completed                           => Task.unit
          case ExecutionStatus.Stopped                             => Task.unit
          case ExecutionStatus.Failed(_) =>
            val retryStrategy = RetryStrategy.retryOnNonFatal(
              cfg.retry,
              log,
              s"running projection ${metadata.name} from module ${metadata.module}"
            )

            semaphore.withPermit {
              supervised.task.flatMap { control =>
                Task.delay(log.info(s"Restarting projection '${metadata.name}' of module '${metadata.module}'")) >>
                mapRef.update(_.updatedWith(metadata.name)(_.map(_.copy(restarts = supervised.restarts + 1, control = control))))
              }
            }.retryingOnSomeErrors(retryStrategy.retryWhen , retryStrategy.policy, retryStrategy.onError)
        }
      }.interruptWhen(signal)
      .compile
      .drain
  }

  private case class Supervised(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      restarts: Int,
      task: Task[Control],
      control: Control
  ) {
    def description: Task[SupervisedDescription] =
      for {
        status <- control.status
        progress <- control.progress
      } yield
        SupervisedDescription(
          metadata,
          executionStrategy,
          restarts,
          status,
          progress
        )
  }

  private case class Control(
      status: Task[ExecutionStatus],
      progress: Task[ProjectionProgress],
      stop: Task[Unit]
  )

  private class Impl(
      store: ProjectionStore,
      cfg: ProjectionConfig,
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
      signal: SignallingRef[Task, Boolean],
      supervisionFiberRef: Ref[Task, Fiber[Throwable, Unit]]
  ) extends Supervisor {

    override def run(projection: CompiledProjection): Task[ExecutionStatus] = {
      val metadata = projection.metadata
      semaphore.withPermit {
        for {
          map       <- mapRef.get
          _         <- map.get(metadata.name) match {
                         // if a projection with the same name already exists remove from the map and stop it, it will
                         // be re-created
                         case Some(value) => mapRef.update(_ - metadata.name) >> value.control.stop
                         case None        => Task.unit
                       }
          task       = controlTask(projection)
          control   <- task
          supervised = Supervised(metadata, projection.executionStrategy, 0, task, control)
          _         <- mapRef.set(map + (metadata.name -> supervised))
          status    <- control.status
        } yield status
      }
    }

    private def controlTask(projection: CompiledProjection): Task[Control] =
      if (!projection.executionStrategy.shouldRun(projection.metadata.name, cfg.cluster))
        Task.delay(log.debug(s"Ignoring projection '${projection.metadata.name}' of module '${projection.metadata.module}' with strategy ${projection.executionStrategy}")) >>
        Task.pure(ignored)
      else
        Task.delay(log.info(s"Starting projection '${projection.metadata.name}' of module '${projection.metadata.module}' with strategy ${projection.executionStrategy}")) >>
        startProjection(projection).map { p =>
          Control(
            p.executionStatus,
            p.currentProgress,
            p.stop()
          )
        }

    private def startProjection(projection: CompiledProjection): Task[Projection] = {
      val (fetchProgress, saveProgress) = projection.executionStrategy match {
        case PersistentSingleNode =>
          (store.offset(projection.metadata.name), store.save(projection.metadata, _))
        case TransientSingleNode | EveryNode =>
          (UIO.none, (_: ProjectionProgress) => UIO.unit)
      }
      Projection(projection, fetchProgress, saveProgress)(cfg.progress)
    }

    private def stopProjection(s: Supervised) =
      s.control.stop.onErrorHandleWith { error =>
        Task.delay(log.error(s"Projection '${s.metadata.name}' of module '${s.metadata.module}' encountered an error during supervisor shutdown.", error))
      }

    override def destroy(name: String): Task[Option[ExecutionStatus]] =
      semaphore.withPermit {
        for {
          map <- mapRef.get
          status   <- map.get(name).traverse { s =>
            if (!s.executionStrategy.shouldRun(name, cfg.cluster))
              Task.delay(log.info(s"Projection '${s.metadata.name}' of module '${s.metadata.module}' does not belong to this node. Skipping...")) >>
                Task.pure(ExecutionStatus.Ignored)
            else
              Task.delay(log.info(s"Destroying projection '${s.metadata.name}' of module '${s.metadata.module}'")) >>
                stopProjection(s) >> Task.when(s.executionStrategy == PersistentSingleNode)(store.delete(name)) >>
                Task.pure(ExecutionStatus.Stopped)
          }
          _   <- mapRef.set(map - name)
        } yield status
      }

    override def describe(name: String): Task[Option[SupervisedDescription]] =
      mapRef.get.flatMap {
        _.get(name).traverse(_.description)
      }

    override def stop(): Task[Unit] =
      for {
        _     <- Task.delay(log.info(s"Stopping supervisor and all its running projections"))
        _     <- signal.set(true)
        fiber <- supervisionFiberRef.get
        _     <- fiber.join
        _     <- semaphore.withPermit {
          for {
            supervised <- mapRef.get.map(_.values.toList)
            _ <- Task.delay(log.error(s"Stopping ${supervised.size} projection(s)..."))
            _ <- supervised.traverse { s => stopProjection(s) }
          } yield ()
        }
      } yield ()
  }

}
