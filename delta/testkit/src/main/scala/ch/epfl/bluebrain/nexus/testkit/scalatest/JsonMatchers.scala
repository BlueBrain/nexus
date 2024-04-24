package ch.epfl.bluebrain.nexus.testkit.scalatest

import io.circe.{Decoder, Json}
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import scala.reflect.ClassTag

object JsonMatchers {
  def field[A: Decoder: ClassTag](key: String, expectedValue: A)(implicit
      ev: Null <:< A
  ): HavePropertyMatcher[Json, A] = HavePropertyMatcher { json =>
    val actual = json.hcursor.downField(key).as[A].toOption
    HavePropertyMatchResult(
      actual.contains(expectedValue),
      key,
      expectedValue,
      actual.orNull
    )
  }
}
