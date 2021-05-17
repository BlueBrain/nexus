package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

trait PluginDef {

  private val parseOptions   = ConfigParseOptions.defaults().setAllowMissing(false)
  private val resolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)

  /**
    * Distage module definition for this plugin.
    */
  def module: ModuleDef

  /**
    * Plugin description
    */
  def info: PluginDescription

  /**
    * @return the plugin configuration filename
    */
  def configFileName: String = s"${info.name}.conf"

  /**
    * The priority of this plugin.
    * This value will decide the order in which this plugin is executed compared to the rest of the plugins.
    * It affects Routes ordering and classpath ordering.
    *
    * The value is retrieved from the plugin configuration inside the priority field
    */
  def priority: Int =
    ConfigFactory
      .load(getClass.getClassLoader, configFileName, parseOptions, resolveOptions)
      .getConfig(s"plugins.${info.name.value}")
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
