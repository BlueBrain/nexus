package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.{KamonMetricComponent, KamonMonitoring, KamonMonitoringCats}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.KamonSyntax.{KamonCatsOps, KamonMonixOps}
import monix.bio.{IO => BIO}

trait KamonSyntax {
  implicit final def kamonSyntax[E, A](io: BIO[E, A]): KamonMonixOps[E, A] = new KamonMonixOps(io)

  implicit final def kamonSyntax[E, A](io: IO[A]): KamonCatsOps[A] = new KamonCatsOps(io)
}

object KamonSyntax {
  final class KamonMonixOps[E, A](private val io: BIO[E, A]) extends AnyVal {

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
    ): BIO[E, A] =
      KamonMonitoring.operationName(name, component, tags, takeSamplingDecision)(io)

    def span(name: String, tags: Map[String, Any] = Map.empty, takeSamplingDecision: Boolean = true)(implicit
        component: KamonMetricComponent
    ): BIO[E, A] =
      named(name, component.value, tags, takeSamplingDecision)
  }

  final class KamonCatsOps[A](private val io: IO[A]) extends AnyVal {

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
      KamonMonitoringCats.operationName(name, component, tags, takeSamplingDecision)(io)

    def span(name: String, tags: Map[String, Any] = Map.empty, takeSamplingDecision: Boolean = true)(implicit
        component: KamonMetricComponent
    ): IO[A] = named(name, component.value, tags, takeSamplingDecision)
  }

}
