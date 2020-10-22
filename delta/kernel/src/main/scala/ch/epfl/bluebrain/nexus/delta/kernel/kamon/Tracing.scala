package ch.epfl.bluebrain.nexus.delta.kernel.kamon

import cats.effect.ExitCase
import kamon.Kamon
import kamon.tag.TagSet
import kamon.trace.Span
import monix.bio.Cause.Termination
import monix.bio.{Cause, IO, UIO}

object Tracing {

  /**
    * Wraps the `io` effect in a new span with the provided name and tags. The created span is marked as finished after
    * the effect is completed or cancelled.
    *
    * @param name                 the span name
    * @param component            the component name
    * @param tags                 the collection of tags to apply to the span
    * @param takeSamplingDecision if true, it ensures that a Sampling Decision is taken in case none has been taken so far
    * @param io                   the effect to trace
    * @return the same effect wrapped within a named span
    */
  def operationName[E, A](
      name: String,
      component: String,
      tags: Map[String, Any] = Map.empty,
      takeSamplingDecision: Boolean = true
  )(io: IO[E, A]): IO[E, A] =
    buildSpan(name, component, tags).bracketCase(_ => io) {
      case (span, ExitCase.Completed)    => finishSpan(span, takeSamplingDecision)
      case (span, ExitCase.Error(cause)) => failSpan(span, cause, takeSamplingDecision)
      case (span, ExitCase.Canceled)     => finishSpan(span.tag("cancel", value = true), takeSamplingDecision)
    }

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
      if (takeSamplingDecision) span.takeSamplingDecision()
      span.finish()
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
