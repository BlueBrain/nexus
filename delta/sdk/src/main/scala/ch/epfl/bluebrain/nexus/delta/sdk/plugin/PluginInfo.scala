package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.model.Name

/**
  * Representation of plugin information.
  *
  * @param name    plugin name
  * @param version plugin version
  */
final case class PluginInfo(name: Name, version: String) {
  override def toString: String = s"$name $version"
}
