package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginStatus.Status

/**
  * Representation of a [[Plugin]] status.
  *
  * @param name    name of the [[Plugin]]
  * @param version version of the [[Plugin]]
  * @param status  status of the [[Plugin]]
  */
final case class PluginStatus(name: String, version: String, status: Status) {}

object PluginStatus {

  /**
    * Plugin status.
    */
  sealed trait Status extends Product with Serializable
  object Status {

    /**
      * Status signaling that the [[Plugin]] is up and running correctly.
      */
    final case object Up extends Status

    /**
      * Status signaling that the [[Plugin]] is currently unavailable.
      */
    final case object Down extends Status
  }

}
