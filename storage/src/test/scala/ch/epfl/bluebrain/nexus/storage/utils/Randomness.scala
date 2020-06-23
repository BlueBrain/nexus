package ch.epfl.bluebrain.nexus.storage.utils

import scala.util.Random

trait Randomness {

  def genString(size: Int = 10): String = Random.alphanumeric.take(size).mkString

  final def genInt(max: Int = 100): Int = Random.nextInt(max)

}

object Randomness extends Randomness
