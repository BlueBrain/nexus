package ch.epfl.bluebrain.nexus.delta.kernel

object Hex {

  /**
    * Convert the array of bytes to a string of the hexadecimal values
    */
  def valueOf(value: Array[Byte]): String = value.map("%02x".format(_)).mkString

}
