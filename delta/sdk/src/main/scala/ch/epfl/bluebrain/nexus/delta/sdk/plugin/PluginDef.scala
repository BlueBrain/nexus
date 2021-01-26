package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
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
    * @return the plugin configuration filename
    */
  def configFileName: String = s"${info.name.value}.conf"

  /**
    * The priority of this plugin.
    * This value will decide the order in which this plugin is executed compared to the rest of the plugins.
    * It affects Routes ordering and classpath ordering.
    *
    * The value is retrieved from the plugin configuration inside the priority field
    */
  def priority: Int =
    ConfigFactory
      .load(
        getClass.getClassLoader,
        configFileName,
        ConfigParseOptions.defaults().setAllowMissing(false),
        ConfigResolveOptions.defaults().setAllowUnresolved(true)
      )
      .getConfig(info.name.value)
      .getInt("priority")

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
