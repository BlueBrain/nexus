package ch.epfl.bluebrain.nexus.delta.sourcing.rejection

abstract class Rejection extends Exception with Product with Serializable {

  def reason: String

}
