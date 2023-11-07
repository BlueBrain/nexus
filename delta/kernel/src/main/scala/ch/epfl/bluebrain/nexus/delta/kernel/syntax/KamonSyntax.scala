package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.{KamonMetricComponent, KamonMonitoring}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.KamonSyntax.KamonOps

trait KamonSyntax {

  implicit final def kamonSyntax[A](io: IO[A]): KamonOps[A] = new KamonOps(io)
}

object KamonSyntax {

  final class KamonOps[A](private val io: IO[A]) extends AnyVal {

    /**
      * Wraps the effect in a new span with the provided name and tags. The created span is marked as finished after the
      * effect is completed or cancelled.
      *
      * @param name
      *   the span name
      * @param component
      *   the component name
      * @param tags
      *   the collection of tags to apply to the span
      * @param takeSamplingDecision
      *   if true, it ensures that a Sampling Decision is taken in case none has been taken so far
      * @return
      *   the same effect wrapped within a named span
      */
    def named(
        name: String,
        component: String,
        tags: Map[String, Any] = Map.empty,
        takeSamplingDecision: Boolean = true
    ): IO[A] =
      KamonMonitoring.operationName(name, component, tags, takeSamplingDecision)(io)

    def span(name: String, tags: Map[String, Any] = Map.empty, takeSamplingDecision: Boolean = true)(implicit
        component: KamonMetricComponent
    ): IO[A] = named(name, component.value, tags, takeSamplingDecision)
  }

}
