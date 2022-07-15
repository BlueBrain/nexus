package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import monix.bio.{Fiber, Task}

// TODO: add docs and tests
trait Supervisor {

  def supervise(projection: CompiledProjection, executionStrategy: ExecutionStrategy): Task[Unit]

  def unSupervise(name: String): Task[Unit]

  def stop(): Task[Unit]

}

object Supervisor {

  def apply(store: ProjectionStore, cfg: ProjectionConfig): Task[Supervisor] =
    for {
      semaphore      <- Semaphore[Task](1L)
      mapRef         <- Ref.of[Task, Map[String, Supervised]](Map.empty)
      supervision    <- supervisionTask(semaphore, mapRef).start
      supervisionRef <- Ref.of[Task, Fiber[Throwable, Unit]](supervision)
    } yield new Impl(store, cfg, semaphore, mapRef, supervisionRef)

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

  // TODO: implement supervision logic (traverse and handle failures, stops etc.)
  private def supervisionTask(semaphore: Semaphore[Task], mapRef: Ref[Task, Map[String, Supervised]]): Task[Unit] = ???

  private class Impl(
      store: ProjectionStore,
      cfg: ProjectionConfig,
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
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

    override def stop(): Task[Unit] =
      for {
        fiber <- supervisionFiberRef.get
        _     <- fiber.cancel
        _     <- fiber.join
        map   <- mapRef.get
        _     <- map.values.toList.traverse(_.control.stop)
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
