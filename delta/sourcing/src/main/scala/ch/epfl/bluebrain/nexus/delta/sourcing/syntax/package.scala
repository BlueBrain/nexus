package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, InstantSyntax, TaskSyntax}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.CancelableStreamSyntax

package object syntax
    extends OffsetSyntax
    with ProjectionStreamSyntax
    with ClassTagSyntax
    with TaskSyntax
    with InstantSyntax
    with CancelableStreamSyntax
