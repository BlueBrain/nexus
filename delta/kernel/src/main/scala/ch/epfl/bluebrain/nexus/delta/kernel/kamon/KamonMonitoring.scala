package ch.epfl.bluebrain.nexus.delta.kernel.kamon

import cats.effect.{IO, Outcome}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import com.typesafe.config.Config
import kamon.tag.TagSet
import kamon.trace.Span
import kamon.{Kamon, Tracing}

import scala.concurrent.duration._

object KamonMonitoring {

  private val logger = Logger[Tracing]

  def enabled: Boolean =
    sys.env.getOrElse("KAMON_ENABLED", "true").toBooleanOption.getOrElse(true)

  /**
    * Initialize Kamon with the provided config
    * @param config
    *   the configuration
    */
  def initialize(config: Config): IO[Unit] =
    IO.whenA(enabled) {
      logger.info("Initializing Kamon") >> IO.blocking(Kamon.init(config))
    }

  /**
    * Terminate Kamon
    */
  def terminate: IO[Unit] =
    IO.whenA(enabled) {
      IO.fromFuture { IO.blocking { Kamon.stopModules() } }
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
        case (span, Outcome.Succeeded(_))   => finishSpan(span, takeSamplingDecision)
        case (span, Outcome.Errored(cause)) => failSpan(span, cause, takeSamplingDecision)
        case (span, Outcome.Canceled())     => finishSpan(span.tag("cancel", value = true), takeSamplingDecision)
      }
    else io
  }.onError { e => logger.debug(e)(e.getMessage) }

  private def buildSpan(name: String, component: String, tags: Map[String, Any]): IO[Span] =
    IO.blocking {
      Kamon
        .serverSpanBuilder(name, component)
        .asChildOf(Kamon.currentSpan())
        .tagMetrics(TagSet.from(tags))
        .start()
    }

  private def finishSpan(span: Span, takeSamplingDecision: Boolean): IO[Unit] =
    IO.blocking {
      val s = if (takeSamplingDecision) span.takeSamplingDecision() else span
      s.finish()
    }

  private def failSpan(span: Span, throwable: Throwable, takeSamplingDecision: Boolean): IO[Unit] =
    IO.blocking {
      span.tag("error", value = true).fail(throwable)
    } >> finishSpan(span, takeSamplingDecision)

}
