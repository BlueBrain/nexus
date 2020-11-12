package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.ResolverPriorityIntervalError

/**
  * A safe representation of a resolver priority
  */
final case class Priority private (value: Int) extends AnyVal

object Priority {

  private val min = 0
  private val max = 1000

  /**
    * Attempts to get a priority from an integer
    */
  def apply(value: Int): Either[ResolverPriorityIntervalError, Priority] =
    if (value >= min && value <= max)
      Right(new Priority(value))
    else
      Left(ResolverPriorityIntervalError(value, min, max))

  /**
    * Construct a priority from an integer without validation
    */
  def unsafe(value: Int) = new Priority(value)

}
