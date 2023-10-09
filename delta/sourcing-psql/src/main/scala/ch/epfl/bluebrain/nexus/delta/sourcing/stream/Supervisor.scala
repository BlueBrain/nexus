package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.Resource
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStatus.Ignored
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.{EveryNode, PersistentSingleNode, TransientSingleNode}
import fs2.Stream
import fs2.concurrent.SignallingRef
import monix.bio.{Fiber, Task, UIO}

import scala.concurrent.duration._

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
    * Supervises the execution of the provided `projection`. A second call to this method with a projection with the
    * same name will cause the current projection to be stopped and replaced by the new one.
    * @param projection
    *   the projection to supervise
    * @param init
    *   an initialize task to perform before starting
    * @see
    *   [[Supervisor]]
    */
  def run(projection: CompiledProjection, init: Task[Unit]): Task[ExecutionStatus]

  /**
    * Supervises the execution of the provided `projection`. A second call to this method with a projection with the
    * same name will cause the current projection to be stopped and replaced by the new one.
    * @param projection
    *   the projection to supervise
    * @see
    *   [[Supervisor]]
    */
  def run(projection: CompiledProjection): Task[ExecutionStatus] = run(projection, Task.unit)

  /**
    * Restart the given projection from the beginning
    * @param name
    *   the name of the projection
    */
  def restart(name: String): Task[Option[ExecutionStatus]]

  /**
    * Stops the projection with the provided `name` and removes it from supervision. It performs a noop if the
    * projection does not exist or it is not running on the current node. It executes the provided finalizer after the
    * projection is stopped.
    * @param name
    *   the name of the projection
    * @param clear
    *   the task to be executed after the projection is destroyed
    */
  def destroy(name: String, clear: Task[Unit]): Task[Option[ExecutionStatus]]

  /**
    * Stops the projection with the provided `name` and removes it from supervision. It performs a noop if the
    * projection does not exist or it is not running on the current node. It executes the provided finalizer after the
    * projection is stopped.
    * @param name
    *   the name of the projection
    */
  def destroy(name: String): Task[Option[ExecutionStatus]] = destroy(name, Task.unit)

  /**
    * Returns the status of the projection with the provided `name`, if a projection with such name exists.
    * @param name
    *   the name of the projection
    */
  def describe(name: String): Task[Option[SupervisedDescription]]

  /**
    * Returns the list of all running projections under this supervisor.
    * @param descriptionFilter
    *   function that indicates when a `SupervisedDescription` should be ignored. Defaults to filtering out
    *   `SupervisedDescription`s with "Ignored" `ExecutionStatus`
    * @return
    *   a list of the currently running projections
    */
  def getRunningProjections(
      descriptionFilter: SupervisedDescription => Option[SupervisedDescription] = desc =>
        Option.when(desc.status != Ignored)(desc)
  ): UIO[List[SupervisedDescription]]

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

  private[sourcing] val watchRestartMetadata = ProjectionMetadata("system", "watch-restarts", None, None)
  private[sourcing] val purgeRestartMetadata = ProjectionMetadata("system", "purge-projection-restarts", None, None)

  /**
    * Constructs a new [[Supervisor]] instance using the provided `store` and `cfg`.
    *
    * @param projections
    *   the projections module
    * @param projectionErrors
    *   the projections error module
    * @param cfg
    *   the projection configuration
    */
  def apply(
      projections: Projections,
      projectionErrors: ProjectionErrors,
      cfg: ProjectionConfig
  ): Resource[Task, Supervisor] = {
    def init: Task[Supervisor] =
      for {
        _              <- log.info("Starting Delta supervisor")
        semaphore      <- Semaphore[Task](1L)
        mapRef         <- Ref.of[Task, Map[String, Supervised]](Map.empty)
        signal         <- SignallingRef[Task, Boolean](false)
        supervision    <- supervisionTask(semaphore, mapRef, signal, cfg).start
        supervisionRef <- Ref.of[Task, Fiber[Throwable, Unit]](supervision)
        supervisor      =
          new Impl(projections, projectionErrors.saveFailedElems, cfg, semaphore, mapRef, signal, supervisionRef)
        _              <- watchRestarts(supervisor, projections)
        _              <- purgeRestarts(supervisor, projections, cfg.deleteExpiredEvery)
        _              <- log.info("Delta supervisor is up")
      } yield supervisor

    Resource.make[Task, Supervisor](init)(_.stop())
  }

  private def createRetryStrategy(cfg: ProjectionConfig, metadata: ProjectionMetadata, action: String) =
    RetryStrategy.retryOnNonFatal(
      cfg.retry,
      log,
      s"$action projection '${metadata.name}' from module '${metadata.module}'"
    )

  private def supervisionTask(
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
      signal: SignallingRef[Task, Boolean],
      cfg: ProjectionConfig
  ): Task[Unit] = {
    Stream
      .awakeEvery[Task](cfg.supervisionCheckInterval)
      .evalTap(_ => log.debug("Checking projection statuses"))
      .evalMap(_ => mapRef.get)
      .flatMap(map => Stream.iterable(map.values))
      .evalMap { supervised =>
        val metadata = supervised.metadata
        supervised.control.status.flatMap {
          case ExecutionStatus.Ignored           => Task.unit
          case ExecutionStatus.Pending           => Task.unit
          case ExecutionStatus.Running           => Task.unit
          case ExecutionStatus.Completed         => Task.unit
          case ExecutionStatus.Stopped           => Task.unit
          case ExecutionStatus.Failed(throwable) =>
            val retryStrategy = createRetryStrategy(cfg, metadata, "running")
            val errorMessage  =
              s"The projection '${metadata.name}' from module '${metadata.module}' failed and will be restarted."
            log.error(throwable)(errorMessage) >>
              semaphore
                .withPermit { restartProjection(supervised, mapRef) }
                .retry(retryStrategy)
        }
      }
      .interruptWhen(signal)
      .compile
      .drain
  }

  protected def restartProjection(supervised: Supervised, mapRef: Ref[Task, Map[String, Supervised]]): Task[Unit] = {
    val metadata = supervised.metadata
    supervised.task.flatMap { control =>
      mapRef.update(
        _.updatedWith(metadata.name)(_.map(_.copy(restarts = supervised.restarts + 1, control = control)))
      )
    }
  }

  private def watchRestarts(supervisor: Supervisor, projections: Projections) = {
    supervisor.run(
      CompiledProjection.fromStream(
        watchRestartMetadata,
        ExecutionStrategy.EveryNode,
        (offset: Offset) =>
          projections
            .restarts(offset)
            .evalMap {
              case s: SuccessElem[ProjectionRestart] =>
                supervisor.restart(s.value.name).flatMap { status =>
                  if (status.exists(_ != ExecutionStatus.Ignored))
                    projections.acknowledgeRestart(s.offset).as(s.void)
                  else
                    Task.pure(s.dropped)
                }
              case other                             => Task.pure(other.void)
            }
      )
    )
  }

  private def purgeRestarts(supervisor: Supervisor, projections: Projections, deleteExpiredEvery: FiniteDuration) = {
    val stream = Stream
      .awakeEvery[Task](deleteExpiredEvery)
      .evalTap(_ => projections.deleteExpiredRestarts())
      .drain
    supervisor
      .run(
        CompiledProjection.fromStream(
          purgeRestartMetadata,
          ExecutionStrategy.TransientSingleNode,
          _ => stream
        )
      )
  }

  final private case class Supervised(
      metadata: ProjectionMetadata,
      executionStrategy: ExecutionStrategy,
      restarts: Int,
      task: Task[Control],
      control: Control
  ) {
    def description: Task[SupervisedDescription] =
      for {
        status   <- control.status
        progress <- control.progress
      } yield SupervisedDescription(
        metadata,
        executionStrategy,
        restarts,
        status,
        progress
      )
  }

  final private case class Control(
      status: Task[ExecutionStatus],
      progress: Task[ProjectionProgress],
      stop: Task[Unit]
  )

  private class Impl(
      projections: Projections,
      saveFailedElems: (ProjectionMetadata, List[FailedElem]) => UIO[Unit],
      cfg: ProjectionConfig,
      semaphore: Semaphore[Task],
      mapRef: Ref[Task, Map[String, Supervised]],
      signal: SignallingRef[Task, Boolean],
      supervisionFiberRef: Ref[Task, Fiber[Throwable, Unit]]
  ) extends Supervisor {

    override def run(projection: CompiledProjection, init: Task[Unit]): Task[ExecutionStatus] = {
      val metadata = projection.metadata
      semaphore.withPermit {
        for {
          supervised <- mapRef.get.map(_.get(metadata.name))
          _          <- supervised.traverse { s =>
                          // if a projection with the same name already exists remove from the map and stop it, it will
                          // be re-created
                          log.info(s"Stopping existing projection '${metadata.module}/${metadata.name}'") >>
                            mapRef.update(_ - metadata.name) >> s.control.stop
                        }
          task        = controlTask(projection, init)
          control    <- task
          supervised  = Supervised(metadata, projection.executionStrategy, 0, task, control)
          _          <- mapRef.update(_ + (metadata.name -> supervised))
          status     <- control.status
        } yield status
      }
    }

    private def controlTask(projection: CompiledProjection, init: Task[Unit]): Task[Control] = {
      val metadata = projection.metadata
      val strategy = projection.executionStrategy
      if (!strategy.shouldRun(metadata.name, cfg.cluster))
        log.debug(s"Ignoring '${metadata.module}/${metadata.name}' with strategy '$strategy'.").as(ignored)
      else
        log.info(s"Starting '${metadata.module}/${metadata.name}' with strategy '$strategy'.") >>
          init >>
          startProjection(projection).map { p =>
            Control(
              p.executionStatus,
              p.currentProgress,
              p.stop()
            )
          }
    }

    private def startProjection(projection: CompiledProjection): Task[Projection] = {
      val (fetchProgress, saveProgress, saveErrors) = projection.executionStrategy match {
        case PersistentSingleNode            =>
          (
            projections.progress(projection.metadata.name),
            projections.save(projection.metadata, _),
            saveFailedElems(projection.metadata, _)
          )
        case TransientSingleNode | EveryNode =>
          (UIO.none, (_: ProjectionProgress) => UIO.unit, saveFailedElems(projection.metadata, _))
      }
      Projection(projection, fetchProgress, saveProgress, saveErrors)(cfg.batch)
    }

    def restart(name: String): Task[Option[ExecutionStatus]] =
      semaphore.withPermit {
        for {
          supervised <- mapRef.get.map(_.get(name))
          status     <- supervised.traverse { s =>
                          val metadata = s.metadata
                          if (!s.executionStrategy.shouldRun(name, cfg.cluster))
                            log
                              .info(s"'${metadata.module}/${metadata.name}' is ignored. Skipping restart...")
                              .as(ExecutionStatus.Ignored)
                          else {
                            for {
                              _      <- log.info(s"Restarting '${metadata.module}/${metadata.name}'...")
                              _      <- stopProjection(s)
                              _      <- Task.when(s.executionStrategy == PersistentSingleNode)(
                                          projections.reset(metadata.name)
                                        )
                              _      <- Supervisor.restartProjection(s, mapRef)
                              status <- s.control.status
                            } yield status
                          }
                        }
        } yield status
      }

    override def destroy(name: String, onDestroy: Task[Unit]): Task[Option[ExecutionStatus]] = {
      semaphore.withPermit {
        for {
          supervised <- mapRef.get.map(_.get(name))
          status     <- supervised.traverse { s =>
                          val metadata      = s.metadata
                          val retryStrategy = createRetryStrategy(cfg, metadata, "destroying")
                          if (!s.executionStrategy.shouldRun(name, cfg.cluster))
                            log
                              .info(s"'${metadata.module}/${metadata.name}' is ignored. Skipping...")
                              .as(ExecutionStatus.Ignored)
                          else {
                            for {
                              _      <- log.info(s"Destroying '${metadata.module}/${metadata.name}'...")
                              _      <- stopProjection(s)
                              _      <- Task.when(s.executionStrategy == PersistentSingleNode)(projections.delete(name))
                              _      <- onDestroy
                                          .retry(retryStrategy)
                                          .onErrorHandle(_ => ())
                              status <- s.control.status
                                          .restartUntil(e => e == ExecutionStatus.Completed || e == ExecutionStatus.Stopped)
                                          .timeout(3.seconds)
                            } yield status.getOrElse(ExecutionStatus.Stopped)
                          }
                        }
          _          <- mapRef.update(_ - name)
        } yield status
      }
    }

    private def stopProjection(s: Supervised) =
      s.control.stop.onErrorHandleWith { e =>
        log.error(e)(s"'${s.metadata.module}/${s.metadata.name}' encountered an error during shutdown.")
      }

    override def describe(name: String): Task[Option[SupervisedDescription]] =
      mapRef.get.flatMap {
        _.get(name).traverse(_.description)
      }

    override def getRunningProjections(
        descriptionFilter: SupervisedDescription => Option[SupervisedDescription] = desc =>
          Option.when(desc.status != Ignored)(desc)
    ): UIO[List[SupervisedDescription]] = {
      for {
        supervised   <- mapRef.get.map(_.values.toList)
        descriptions <- supervised.traverseFilter { _.description.map(descriptionFilter) }
      } yield descriptions
    }.hideErrors

    override def stop(): Task[Unit] =
      for {
        _     <- log.info(s"Stopping supervisor and all its running projections")
        _     <- signal.set(true)
        fiber <- supervisionFiberRef.get
        _     <- fiber.join
        _     <- semaphore.withPermit {
                   for {
                     supervised <- mapRef.get.map(_.values.toList)
                     _          <- log.info(s"Stopping ${supervised.size} projection(s)...")
                     _          <- supervised.traverse { s => stopProjection(s) }
                   } yield ()
                 }
      } yield ()
  }

}
