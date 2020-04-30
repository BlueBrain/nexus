package ch.epfl.bluebrain.nexus.cli.utils

import org.scalactic.{source, Prettifier}
import org.scalatest.matchers.should.Matchers

trait ShouldMatchers {

  implicit def convertToAnyShouldWrapper[T](
      o: T
  )(implicit pos: source.Position, prettifier: Prettifier): Matchers.AnyShouldWrapper[T] =
    new Matchers.AnyShouldWrapper(o, pos, prettifier)

}

object ShouldMatchers extends ShouldMatchers
