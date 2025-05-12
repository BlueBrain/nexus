package ch.epfl.bluebrain.nexus.delta.sourcing.query

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ElemQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ElemQueryConfig.{DelayConfig, PassivationConfig, StopConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshOrStop.Outcome
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshOrStop.Outcome.*
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectActivitySignals
import fs2.Stream
import fs2.concurrent.SignallingRef

/**
  * Computes the outcome to apply when all elements are consumed by a projection
  */
trait RefreshOrStop {
  def run: IO[Outcome]
}

object RefreshOrStop {

  sealed trait Outcome

  object Outcome {
    case object Stopped            extends Outcome
    sealed trait Continue          extends Outcome
    case object Delayed            extends Continue
    case object NoSignal           extends Continue
    case object DelayedPassivation extends Continue
    case object Passivated         extends Continue
  }

  private val logger = Logger[RefreshOrStop.type]

  /**
    * Generates an instance from the provided parameters
    * @param scope
    *   scope of the projection (ex: root/project)
    * @param config
    *   the elem query configuration of the projection
    * @param activitySignals
    *   the activity signals (used only in the case of a passivation config)
    */
  def apply(scope: Scope, config: ElemQueryConfig, activitySignals: ProjectActivitySignals): RefreshOrStop =
    new RefreshOrStop {
      override def run: IO[Outcome] = {
        (config, scope) match {
          case (_: StopConfig, _)                             => IO.pure(Stopped)
          case (d: DelayConfig, _)                            => IO.sleep(d.delay).as(Delayed)
          case (w: PassivationConfig, Scope.Project(project)) =>
            activitySignals.apply(project).flatMap {
              case Some(signal) =>
                signal.get.flatMap {
                  case true  =>
                    logger.debug(s"Project '$project' is active, continue after ${w.delay}") >>
                      IO.sleep(w.delay).as(DelayedPassivation)
                  case false => passivate(project, signal)
                }
              case None         =>
                logger.debug(s"No signal has been found for project '$project', continue after ${w.delay}") >> IO
                  .sleep(w.delay)
                  .as(NoSignal)
            }
          case (c, s)                                         =>
            // Passivation is only available at the project scope
            IO.raiseError(new IllegalStateException(s"'$c' and '$s' is not a valid combination, it should not happen"))
        }
      }
    }

  private def passivate(project: ProjectRef, signal: SignallingRef[IO, Boolean]) =
    for {
      _        <- logger.info(s"Project '$project' is inactive, pausing until some activity is seen again.")
      duration <- Stream.never[IO].interruptWhen(signal).compile.drain.timed.map(_._1.toCoarsest)
      _        <- logger.info(s"Project '$project' is active again after `$duration`, querying will resume.")
    } yield Passivated

}
