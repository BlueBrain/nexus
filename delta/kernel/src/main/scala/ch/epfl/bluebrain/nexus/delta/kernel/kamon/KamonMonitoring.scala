package ch.epfl.bluebrain.nexus.delta.kernel.kamon

import cats.effect.ExitCase
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import kamon.{Kamon, Tracing}
import kamon.tag.TagSet
import kamon.trace.Span
import monix.bio.Cause.Termination
import monix.bio.{Cause, IO, Task, UIO}

import scala.concurrent.duration._

object KamonMonitoring {

  private val logger: Logger = Logger[Tracing]

  def enabled: Boolean =
    sys.env.getOrElse("KAMON_ENABLED", "true").toBooleanOption.getOrElse(true)

  /**
    * Initialize Kamon with the provided config
    * @param config
    *   the configuration
    */
  def initialize(config: Config): UIO[Unit] =
    UIO.when(enabled) {
      UIO.delay("Initializing Kamon") >> UIO.delay(Kamon.init(config))
    }

  /**
    * Terminate Kamon
    */
  def terminate: Task[Unit] =
    Task.when(enabled) {
      Task
        .deferFuture(Kamon.stopModules())
        .timeout(15.seconds)
        .tapError { e =>
          UIO.delay(logger.error("Something went wrong while terminating Kamon", e))
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
  def operationName[E, A](
      name: String,
      component: String,
      tags: Map[String, Any] = Map.empty,
      takeSamplingDecision: Boolean = true
  )(io: IO[E, A]): IO[E, A] =
    if (enabled)
      buildSpan(name, component, tags).bracketCase(_ => io) {
        case (span, ExitCase.Completed)    => finishSpan(span, takeSamplingDecision)
        case (span, ExitCase.Error(cause)) => failSpan(span, cause, takeSamplingDecision)
        case (span, ExitCase.Canceled)     => finishSpan(span.tag("cancel", value = true), takeSamplingDecision)
      }
    else io

  private def buildSpan(name: String, component: String, tags: Map[String, Any]): UIO[Span] =
    UIO.delay(
      Kamon
        .serverSpanBuilder(name, component)
        .asChildOf(Kamon.currentSpan())
        .tagMetrics(TagSet.from(tags))
        .start()
    )

  private def finishSpan(span: Span, takeSamplingDecision: Boolean): UIO[Unit] =
    UIO.delay {
      val s = if (takeSamplingDecision) span.takeSamplingDecision() else span
      s.finish()
    }

  private def failSpan[E](span: Span, cause: Cause[E], takeSamplingDecision: Boolean): UIO[Unit] =
    cause match {
      case Cause.Error(e)  =>
        UIO.delay {
          span.tag("error", value = true).fail(e.toString)
        } >> finishSpan(span, takeSamplingDecision)
      case Termination(th) =>
        UIO.delay {
          span.tag("error", value = true).fail(th)
        } >> finishSpan(span, takeSamplingDecision)
    }

}
