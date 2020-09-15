package ch.epfl.bluebrain.nexus.delta.sdk.plugin

/**
  * Plugin status.
  */
sealed trait PluginStatus

object PluginStatus {

  /**
    * Status signaling that the [[Plugin]] is up and running correctly.
    */
  final case object Up extends PluginStatus

  /**
    * Status signaling that the [[Plugin]] is starting.
    */
  final case object Starting extends PluginStatus

  /**
    * Status signaling that the [[Plugin]] is currently unavailable.
    */
  final case object Stopped extends PluginStatus

  /**
    * Status signaling that the [[Plugin]] is stopping.
    */
  final case object Stopping extends PluginStatus

  /**
    * Status signaling that the [[Plugin]] plugin failed to start
    * @param reason  reason for failure.
    */
  final case class Failed(reason: String) extends PluginStatus

}
