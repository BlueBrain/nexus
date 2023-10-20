package ch.epfl.bluebrain.nexus.delta.kernel.kamon

import cats.effect.{ContextShift, ExitCase, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import com.typesafe.config.Config
import kamon.tag.TagSet
import kamon.trace.Span
import kamon.{Kamon, Tracing}

import scala.concurrent.duration._

object KamonMonitoringCats {

  private val logger = Logger.cats[Tracing]

  def enabled: Boolean =
    sys.env.getOrElse("KAMON_ENABLED", "true").toBooleanOption.getOrElse(true)

  /**
    * Initialize Kamon with the provided config
    * @param config
    *   the configuration
    */
  def initialize(config: Config): IO[Unit] =
    IO.whenA(enabled) {
      logger.info("Initializing Kamon") >> IO.delay(Kamon.init(config))
    }

  /**
    * Terminate Kamon
    */
  def terminate(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[Unit] =
    IO.whenA(enabled) {
      IO.fromFuture { IO { Kamon.stopModules() } }
        .timeout(15.seconds)
        .onError { e =>
          logger.error(e)("Something went wrong while terminating Kamon")
        }
        .void
    }

  /**
    * Wraps the `io` effect in a new span with the provided name and tags. The created span is marked as finished after
    * the effect is completed or cancelled.
    *
    * @param name
    *   the span name
    * @param component
    *   the component name
    * @param tags
    *   the collection of tags to apply to the span
    * @param takeSamplingDecision
    *   if true, it ensures that a Sampling Decision is taken in case none has been taken so far
    * @param io
    *   the effect to trace
    * @return
    *   the same effect wrapped within a named span
    */
  def operationName[A](
      name: String,
      component: String,
      tags: Map[String, Any] = Map.empty,
      takeSamplingDecision: Boolean = true
  )(io: IO[A]): IO[A] = {
    if (enabled)
      buildSpan(name, component, tags).bracketCase(_ => io) {
        case (span, ExitCase.Completed)    => finishSpan(span, takeSamplingDecision)
        case (span, ExitCase.Error(cause)) => failSpan(span, cause, takeSamplingDecision)
        case (span, ExitCase.Canceled)     => finishSpan(span.tag("cancel", value = true), takeSamplingDecision)
      }
    else io
  }.onError { case e: Rejection => logger.info(e)(e.getMessage) }

  private def buildSpan(name: String, component: String, tags: Map[String, Any]): IO[Span] =
    IO {
      Kamon
        .serverSpanBuilder(name, component)
        .asChildOf(Kamon.currentSpan())
        .tagMetrics(TagSet.from(tags))
        .start()
    }

  private def finishSpan(span: Span, takeSamplingDecision: Boolean): IO[Unit] =
    IO {
      val s = if (takeSamplingDecision) span.takeSamplingDecision() else span
      s.finish()
    }

  private def failSpan(span: Span, throwable: Throwable, takeSamplingDecision: Boolean): IO[Unit] =
    IO.delay {
      span.tag("error", value = true).fail(throwable)
    } >> finishSpan(span, takeSamplingDecision)

}
