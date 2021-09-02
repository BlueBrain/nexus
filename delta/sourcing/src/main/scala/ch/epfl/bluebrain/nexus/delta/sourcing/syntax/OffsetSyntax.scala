package ch.epfl.bluebrain.nexus.delta.sourcing.syntax

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.sourcing.OffsetUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.instances._

import scala.math.Ordering.Implicits._

trait OffsetSyntax {
  implicit final def offsetSyntax(offset: Offset): OffsetSyntaxOps = new OffsetSyntaxOps(offset)
}

final class OffsetSyntaxOps(private val value: Offset) extends AnyVal {

  /**
    * Offset comparison
    *
    * @return
    *   true when ''value'' is greater than the passed ''offset'' or when offset is ''NoOffset'', false otherwise
    */
  def gt(offset: Offset): Boolean =
    offset == NoOffset || value > offset

  /**
    * Format the offset in a meaningful way as text
    */
  def asString: String =
    OffsetUtils.asString(value)
}
