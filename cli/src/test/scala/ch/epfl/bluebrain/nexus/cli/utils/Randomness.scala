package ch.epfl.bluebrain.nexus.cli.utils

import scala.util.Random

trait Randomness {

  def genString(size: Int = 10): String =
    Random.alphanumeric.take(size).mkString

  def genDouble(minInclusive: Double, maxExclusive: Double): Double =
    Random.between(minInclusive, maxExclusive)

}

object Randomness extends Randomness
