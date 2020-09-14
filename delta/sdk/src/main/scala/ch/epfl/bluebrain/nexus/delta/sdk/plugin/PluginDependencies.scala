package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, Permissions}

/**
  * Dependencies passed to the plugin.
  */
trait PluginDependencies {

  /**
    * Permissions API
    */
  def permissions: Permissions

  /**
    * Identities API
    */
  def identities: Identities

}
