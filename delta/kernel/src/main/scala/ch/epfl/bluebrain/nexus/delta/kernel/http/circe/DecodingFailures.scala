package ch.epfl.bluebrain.nexus.delta.kernel.http.circe

import cats.data.NonEmptyList
import cats.syntax.show.*
import io.circe.DecodingFailure

/**
  * Wraps a list of decoding failures as an [[java.lang.Exception]] when using [[accumulatingJsonOf]] to decode JSON
  * messages.
  */
final case class DecodingFailures(failures: NonEmptyList[DecodingFailure]) extends Exception {
  override def getMessage: String = failures.iterator.map(_.show).mkString("\n")
}
