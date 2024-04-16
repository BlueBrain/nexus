package ch.epfl.bluebrain.nexus.ship

sealed trait RunMode extends Product with Serializable

object RunMode {

  final case object Local extends RunMode

  final case object S3 extends RunMode

}
