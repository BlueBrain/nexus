package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task}

import scala.concurrent.duration.FiniteDuration

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
    * Supervises the execution of the provided `projection` using the provided `executionStrategy`. A second call to
    * this method with a projection with the same name will cause the current projection to be stopped and replaced by
    * the new one.
    * @param projection
    *   the projection to supervise
    * @param executionStrategy
    *   the strategy for the projection execution
    * @see
    *   [[Supervisor]]
    */
  def supervise(projection: CompiledProjection, executionStrategy: ExecutionStrategy): Task[Unit]

  /**
    * Stops the projection with the provided `name` and removes it from supervision. It performs a noop if the
    * projection does not exist.
    * @param name
    *   the name of the projection
    */
  def unSupervise(name: String): Task[Unit]

  /**
    * Returns the status of the projection with the provided `name`, if a projection with such name exists.
    * @param name
    *   the name of the projection
    */
  def status(name: String): Task[Option[ExecutionStatus]]

  /**
    * Stops all running projections without removing them from supervision.
    */
  def stop(): Task[Unit]

}

object Supervisor {

  /**
    * Constructs a new [[Supervisor]] instance using the provided `store` and `cfg`.
    * @param store
    *   the projection store for handling offsets
    * @param cfg
    *   the projection configuration
    */
  def apply(store: ProjectionStore, cfg: ProjectionConfig): Task[Supervisor] =
    for {
      semaphore      <- Semaphore[Task](1L)
      mapRef         <- Ref.of[Task, Map[String, Supervised]](Map.empty)
      signal         <- SignallingRef[Task, Boolean](false)
      supervision    <- supervisionTask(semaphore, mapRef, signal, cfg.supervisionCheckInterval).start
      supervisionRef <- Ref.of[Task, Fiber[Throwable, Unit]](supervision)
    } yield new Impl(store, cfg, semaphore, mapRef, signal, supervisionRef)

  private case class Supervised(
      definition: CompiledProjection,
      executionStrategy: ExecutionStrategy,
      task: Task[Control],
      control: Control
  )

  private case class Control(
      status: Task[ExecutionStatus],
      stop: Task[Unit]
  )
  private val ignored = Control(
    status = Task.pure(ExecutionStatus.Ignored),
    stop = Task.unit
  )

  private def supervisionTask(
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
      signal: SignallingRef[Task, Boolean],
      interval: FiniteDuration
  ): Task[Unit] =
    Stream
      .awakeEvery[Task](interval)
      .evalMap(_ => mapRef.get)
      .flatMap(map => Stream.iterable(map.values))
      .evalMap { supervised =>
        supervised.control.status.flatMap {
          case ExecutionStatus.Ignored                                   => Task.unit
          case ExecutionStatus.Pending(_)                                => Task.unit
          case ExecutionStatus.Running(_)                                => Task.unit
          case ExecutionStatus.Passivated(_)                             => Task.unit
          case ExecutionStatus.Completed(_)                              => Task.unit
          case ExecutionStatus.Stopped(_) | ExecutionStatus.Failed(_, _) =>
            // TODO: add a restart delay for failed projections
            semaphore.withPermit {
              supervised.task.flatMap { control =>
                mapRef.update(map => map + (supervised.definition.name -> supervised.copy(control = control)))
              }
            }
        }
      }
      .interruptWhen(signal)
      .compile
      .drain

  private class Impl(
      store: ProjectionStore,
      cfg: ProjectionConfig,
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
      signal: SignallingRef[Task, Boolean],
      supervisionFiberRef: Ref[Task, Fiber[Throwable, Unit]]
  ) extends Supervisor {

    override def supervise(projection: CompiledProjection, executionStrategy: ExecutionStrategy): Task[Unit] =
      semaphore.withPermit {
        for {
          map       <- mapRef.get
          _         <- map.get(projection.name) match {
                         // if a projection with the same name already exists remove from the map and stop it, it will
                         // be re-created
                         case Some(value) => mapRef.update(_ - projection.name) >> value.control.stop
                         case None        => Task.unit
                       }
          task       = controlTask(projection, executionStrategy)
          control   <- task
          supervised = Supervised(projection, executionStrategy, task, control)
          _         <- mapRef.set(map + (projection.name -> supervised))
        } yield ()
      }

    override def unSupervise(name: String): Task[Unit] =
      semaphore.withPermit {
        for {
          map <- mapRef.get
          _   <- map.get(name) match {
                   case Some(value) => value.control.stop
                   case None        => Task.unit
                 }
          _   <- mapRef.set(map - name)
        } yield ()
      }

    override def status(name: String): Task[Option[ExecutionStatus]] =
      mapRef.get.flatMap { map =>
        map.get(name) match {
          case Some(value) => value.control.status.map(Option.apply)
          case None        => Task.pure(None)
        }
      }

    override def stop(): Task[Unit] =
      for {
        _     <- signal.set(true)
        fiber <- supervisionFiberRef.get
        _     <- fiber.join
        _     <- semaphore.withPermit {
                   mapRef.get.flatMap(map => map.values.toList.traverse(_.control.stop))
                 }
      } yield ()

    private def controlTask(projection: CompiledProjection, executionStrategy: ExecutionStrategy): Task[Control] =
      if (!executionStrategy.shouldRun(projection.name, cfg)) Task.pure(ignored)
      else if (executionStrategy.shouldPersist)
        for {
          offset    <- store.offset(projection.name)
          status    <- Ref[Task].of[ExecutionStatus](ExecutionStatus.Pending(offset))
          reference <- projection.persistOffset(store, cfg.persistOffsetInterval).start(offset, status)
          control    = Control(status = status.get, stop = reference.stop())
        } yield control
      else
        for {
          status    <- Ref[Task].of[ExecutionStatus](ExecutionStatus.Pending(ProjectionOffset.empty))
          reference <- projection.start(status)
          control    = Control(status.get, reference.stop())
        } yield control
  }

}
