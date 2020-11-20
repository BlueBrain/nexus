package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.ResolverPriorityIntervalError
import io.circe.{Decoder, Encoder}

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
    Either.cond(
      value >= min && value <= max,
      new Priority(value),
      ResolverPriorityIntervalError(value, min, max)
    )

  /**
    * Construct a priority from an integer without validation
    */
  def unsafe(value: Int) = new Priority(value)

  implicit val projectRefEncoder: Encoder[Priority] = Encoder.encodeInt.contramap(_.value)
  implicit val projectRefDecoder: Decoder[Priority] = Decoder.decodeInt.emap(Priority(_).leftMap(_.getMessage))

}
