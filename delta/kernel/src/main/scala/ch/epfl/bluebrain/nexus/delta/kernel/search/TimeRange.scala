package ch.epfl.bluebrain.nexus.delta.kernel.search

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange.ParseError.*

import java.time.Instant
import scala.util.Try

/**
  * Defines a date time range for search purposes
  */
sealed trait TimeRange extends Product with Serializable

object TimeRange {

  /**
    * Defines a time range taking place after the given instant
    */
  final case class After(value: Instant) extends TimeRange

  /**
    * Defines a time range taking place before the given instant
    */
  final case class Before(value: Instant) extends TimeRange

  /**
    * Defines a time range taking place between the two instants
    */
  final case class Between private[TimeRange] (start: Instant, end: Instant) extends TimeRange

  object Between {
    def unsafe(start: Instant, end: Instant) = new Between(start, end)
  }

  /**
    * Defines a time range with no bounds
    */
  final case object Anytime extends TimeRange

  val default: TimeRange = TimeRange.Anytime

  private val wildCard = "*"

  private val splitRegex = "\\.\\."

  sealed trait ParseError extends Product with Serializable {
    def message: String
  }

  object ParseError {

    final case class InvalidFormat(value: String) extends ParseError {
      def message: String = s"Value '$value' does not follow the expected date range format 'date1..date2'."
    }

    final case class InvalidValue(value: String) extends ParseError {
      def message: String = s"Value '$value' can't be parsed as a datetime"
    }

    final case class InvalidRange(start: String, end: String) extends ParseError {
      def message: String = s"The start date '$start' should be lower the end date '$end'"
    }
  }

  private def parseInstant(value: String) =
    Try(Instant.parse(value)).toEither.leftMap { _ =>
      InvalidValue(value)
    }

  def parse(value: String): Either[ParseError, TimeRange] =
    value.split(splitRegex) match {
      case Array(`wildCard`, `wildCard`) => Right(Anytime)
      case Array(value, `wildCard`)      => parseInstant(value).map(After)
      case Array(`wildCard`, value)      => parseInstant(value).map(Before)
      case Array(startValue, endValue)   =>
        for {
          start <- parseInstant(startValue)
          end   <- parseInstant(endValue)
          _     <- Either.cond(start.isBefore(end), (), InvalidRange(startValue, endValue))
        } yield Between(start, end)
      case _                             =>
        Left(InvalidFormat(value))
    }
}
