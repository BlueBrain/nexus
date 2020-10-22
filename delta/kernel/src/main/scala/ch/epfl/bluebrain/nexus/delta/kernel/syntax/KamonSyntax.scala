package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import ch.epfl.bluebrain.nexus.delta.kernel.kamon.Tracing
import monix.bio.IO

trait KamonSyntax {
  implicit final def kamonSyntax[E, A](io: IO[E, A]): KamonOps[E, A] = new KamonOps(io)
}

final class KamonOps[E, A](private val io: IO[E, A]) extends AnyVal {

  /**
    * Wraps the effect in a new span with the provided name and tags. The created span is marked as finished after
    * the effect is completed or cancelled.
    *
    * @param name                 the span name
    * @param component            the component name
    * @param tags                 the collection of tags to apply to the span
    * @param takeSamplingDecision if true, it ensures that a Sampling Decision is taken in case none has been taken so far
    * @return the same effect wrapped within a named span
    */
  def named(
      name: String,
      component: String,
      tags: Map[String, Any] = Map.empty,
      takeSamplingDecision: Boolean = true
  ): IO[E, A] =
    Tracing.operationName(name, component, tags, takeSamplingDecision)(io)

}
