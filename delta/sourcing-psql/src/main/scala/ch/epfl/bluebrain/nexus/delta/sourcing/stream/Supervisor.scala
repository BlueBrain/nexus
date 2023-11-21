package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Fiber, IO, Resource}

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

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import cats.effect.Ref
import cats.effect.std.Semaphore
import cats.implicits._

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
  def run(projection: CompiledProjection, init: IO[Unit]): IO[ExecutionStatus]

  /**
    * Supervises the execution of the provided `projection`. A second call to this method with a projection with the
    * same name will cause the current projection to be stopped and replaced by the new one.
    * @param projection
    *   the projection to supervise
    * @see
    *   [[Supervisor]]
    */
  def run(projection: CompiledProjection): IO[ExecutionStatus] = run(projection, IO.unit)

  /**
    * Restart the given projection from the beginning
    * @param name
    *   the name of the projection
    */
  def restart(name: String): IO[Option[ExecutionStatus]]

  /**
    * Stops the projection with the provided `name` and removes it from supervision. It performs a noop if the
    * projection does not exist or it is not running on the current node. It executes the provided finalizer after the
    * projection is stopped.
    * @param name
    *   the name of the projection
    * @param clear
    *   the task to be executed after the projection is destroyed
    */
  def destroy(name: String, clear: IO[Unit]): IO[Option[ExecutionStatus]]

  /**
    * Stops the projection with the provided `name` and removes it from supervision. It performs a noop if the
    * projection does not exist or it is not running on the current node. It executes the provided finalizer after the
    * projection is stopped.
    * @param name
    *   the name of the projection
    */
  def destroy(name: String): IO[Option[ExecutionStatus]] = destroy(name, IO.unit)

  /**
    * Returns the status of the projection with the provided `name`, if a projection with such name exists.
    * @param name
    *   the name of the projection
    */
  def describe(name: String): IO[Option[SupervisedDescription]]

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
  ): IO[List[SupervisedDescription]]

  /**
    * Stops all running projections without removing them from supervision.
    */
  def stop(): IO[Unit]

}

object Supervisor {

  private val log = Logger[Supervisor]

  private val ignored = Control(
    status = IO.pure(ExecutionStatus.Ignored),
    progress = IO.pure(ProjectionProgress.NoProgress),
    stop = IO.unit
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
  ): Resource[IO, Supervisor] = {
    def init: IO[Supervisor] =
      for {
        _              <- log.info("Starting Delta supervisor")
        semaphore      <- Semaphore[IO](1L)
        mapRef         <- Ref.of[IO, Map[String, Supervised]](Map.empty)
        signal         <- SignallingRef[IO, Boolean](false)
        supervision    <- supervisionTask(semaphore, mapRef, signal, cfg).start
        supervisionRef <- Ref.of[IO, Fiber[IO, Throwable, Unit]](supervision)
        supervisor      =
          new Impl(projections, projectionErrors.saveFailedElems, cfg, semaphore, mapRef, signal, supervisionRef)
        _              <- watchRestarts(supervisor, projections)
        _              <- purgeRestarts(supervisor, projections, cfg.deleteExpiredEvery)
        _              <- log.info("Delta supervisor is up")
      } yield supervisor

    Resource.make[IO, Supervisor](init)(_.stop())
  }

  private def createRetryStrategy(cfg: ProjectionConfig, metadata: ProjectionMetadata, action: String) =
    RetryStrategy.retryOnNonFatal(
      cfg.retry,
      log,
      s"$action projection '${metadata.name}' from module '${metadata.module}'"
    )

  private def supervisionTask(
      semaphore: Semaphore[IO],
      mapRef: Ref[IO, Map[String, Supervised]],
      signal: SignallingRef[IO, Boolean],
      cfg: ProjectionConfig
  ): IO[Unit] = {
    Stream
      .awakeEvery[IO](cfg.supervisionCheckInterval)
      .evalTap(_ => log.debug("Checking projection statuses"))
      .evalMap(_ => mapRef.get)
      .flatMap(map => Stream.iterable(map.values))
      .evalMap { supervised =>
        val metadata = supervised.metadata
        supervised.control.status.flatMap {
          case ExecutionStatus.Ignored           => IO.unit
          case ExecutionStatus.Pending           => IO.unit
          case ExecutionStatus.Running           => IO.unit
          case ExecutionStatus.Completed         => IO.unit
          case ExecutionStatus.Stopped           => IO.unit
          case ExecutionStatus.Failed(throwable) =>
            val retryStrategy = createRetryStrategy(cfg, metadata, "running")
            val errorMessage  =
              s"The projection '${metadata.name}' from module '${metadata.module}' failed and will be restarted."
            log.error(throwable)(errorMessage) >>
              semaphore.permit
                .use(_ => restartProjection(supervised, mapRef))
                .retry(retryStrategy)
        }
      }
      .interruptWhen(signal)
      .compile
      .drain
  }

  protected def restartProjection(supervised: Supervised, mapRef: Ref[IO, Map[String, Supervised]]): IO[Unit] = {
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
                    IO.pure(s.dropped)
                }
              case other                             => IO.pure(other.void)
            }
      )
    )
  }

  private def purgeRestarts(supervisor: Supervisor, projections: Projections, deleteExpiredEvery: FiniteDuration) = {
    val stream = Stream
      .awakeEvery[IO](deleteExpiredEvery)
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
      task: IO[Control],
      control: Control
  ) {
    def description: IO[SupervisedDescription] =
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
      status: IO[ExecutionStatus],
      progress: IO[ProjectionProgress],
      stop: IO[Unit]
  )

  private class Impl(
      projections: Projections,
      saveFailedElems: (ProjectionMetadata, List[FailedElem]) => IO[Unit],
      cfg: ProjectionConfig,
      semaphore: Semaphore[IO],
      mapRef: Ref[IO, Map[String, Supervised]],
      signal: SignallingRef[IO, Boolean],
      supervisionFiberRef: Ref[IO, Fiber[IO, Throwable, Unit]]
  ) extends Supervisor {

    override def run(projection: CompiledProjection, init: IO[Unit]): IO[ExecutionStatus] = {
      val metadata = projection.metadata
      semaphore.permit.use { _ =>
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

    private def controlTask(projection: CompiledProjection, init: IO[Unit]): IO[Control] = {
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

    private def startProjection(projection: CompiledProjection): IO[Projection] = {
      val (fetchProgress, saveProgress, saveErrors) = projection.executionStrategy match {
        case PersistentSingleNode            =>
          (
            projections.progress(projection.metadata.name),
            projections.save(projection.metadata, _),
            saveFailedElems(projection.metadata, _)
          )
        case TransientSingleNode | EveryNode =>
          (IO.none, (_: ProjectionProgress) => IO.unit, saveFailedElems(projection.metadata, _))
      }
      Projection(projection, fetchProgress, saveProgress, saveErrors)(cfg.batch)
    }

    def restart(name: String): IO[Option[ExecutionStatus]] =
      semaphore.permit.use { _ =>
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
                              _      <- IO.whenA(s.executionStrategy == PersistentSingleNode)(
                                          projections.reset(metadata.name)
                                        )
                              _      <- Supervisor.restartProjection(s, mapRef)
                              status <- s.control.status
                            } yield status
                          }
                        }
        } yield status
      }

    override def destroy(name: String, onDestroy: IO[Unit]): IO[Option[ExecutionStatus]] = {
      semaphore.permit.use { _ =>
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
                              _      <- IO.whenA(s.executionStrategy == PersistentSingleNode)(projections.delete(name))
                              _      <- onDestroy
                                          .retry(retryStrategy)
                                          .handleError(_ => ())
                              status <- s.control.status
                                          .iterateUntil(e => e == ExecutionStatus.Completed || e == ExecutionStatus.Stopped)
                                          .timeout(3.seconds)
                                          .recover { case _: TimeoutException =>
                                            ExecutionStatus.Stopped
                                          }
                            } yield status
                          }
                        }
          _          <- mapRef.update(_ - name)
        } yield status
      }
    }

    private def stopProjection(s: Supervised) =
      s.control.stop.handleErrorWith { e =>
        log.error(e)(s"'${s.metadata.module}/${s.metadata.name}' encountered an error during shutdown.")
      }

    override def describe(name: String): IO[Option[SupervisedDescription]] =
      mapRef.get.flatMap {
        _.get(name).traverse(_.description)
      }

    override def getRunningProjections(
        descriptionFilter: SupervisedDescription => Option[SupervisedDescription] = desc =>
          Option.when(desc.status != Ignored)(desc)
    ): IO[List[SupervisedDescription]] = {
      for {
        supervised   <- mapRef.get.map(_.values.toList)
        descriptions <- supervised.traverseFilter { _.description.map(descriptionFilter) }
      } yield descriptions
    }

    override def stop(): IO[Unit] =
      for {
        _     <- log.info(s"Stopping supervisor and all its running projections")
        _     <- signal.set(true)
        fiber <- supervisionFiberRef.get
        _     <- fiber.join
        _     <- semaphore.permit.use { _ =>
                   for {
                     supervised <- mapRef.get.map(_.values.toList)
                     _          <- log.info(s"Stopping ${supervised.size} projection(s)...")
                     _          <- supervised.traverse { s => stopProjection(s) }
                   } yield ()
                 }
      } yield ()
  }

}
