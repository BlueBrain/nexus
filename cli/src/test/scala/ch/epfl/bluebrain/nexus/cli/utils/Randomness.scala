package ch.epfl.bluebrain.nexus.cli.utils

import scala.util.Random

trait Randomness {

  def genString(size: Int = 10): String =
    Random.alphanumeric.take(size).mkString

}

object Randomness extends Randomness
