package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

trait PluginDef {

  /**
    * Distage module definition for this plugin.
    */
  def module: ModuleDef

  /**
    * Remote context resolution provided by the plugin.
    */
  def remoteContextResolution: RemoteContextResolution

  /**
    * Plugin information
    */
  def info: PluginInfo

  /**
    * The priority of this plugin.
    * This value will decide the order in which this plugin takes is executed compared to the rest of the plugins.
    * It affects Routes ordering and classpath ordering
    */
  def priority: Int

  /**
    * Initialize the plugin.
    *
    * @param locator  distage dependencies [[Locator]]
    *
    * @return [[Plugin]] instance.
    */
  def initialize(locator: Locator): Task[Plugin]

}

object PluginDef {
  implicit val pluginDefOrdering: Ordering[PluginDef] = Ordering.by(_.priority)
}
