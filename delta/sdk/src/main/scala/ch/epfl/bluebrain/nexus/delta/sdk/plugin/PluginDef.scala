package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.PluginDescription
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef

import java.io.File

trait PluginDef {

  /**
    * Distage module definition for this plugin.
    */
  def module: ModuleDef

  /**
    * Plugin description
    */
  def info: PluginDescription

  /**
    * @return
    *   the plugin configuration filename
    */
  def configFileName: String = s"${info.name}.conf"

  /**
    * @return
    *   true if the plugin is enabled, false otherwise
    */
  def enabled: Boolean = pluginConfigObject.getBoolean("enabled")

  /**
    * The priority of this plugin. This value will decide the order in which this plugin is executed compared to the
    * rest of the plugins. It affects Routes ordering and classpath ordering.
    *
    * The value is retrieved from the plugin configuration inside the priority field
    */
  def priority: Int = pluginConfigObject.getInt("priority")

  /**
    * Initialize the plugin.
    *
    * @param locator
    *   distage dependencies [[Locator]]
    *
    * @return
    *   [[Plugin]] instance.
    */
  def initialize(locator: Locator): IO[Plugin]

  protected lazy val pluginConfigObject: Config = PluginDef.load(getClass.getClassLoader, info.name, configFileName)

}

object PluginDef {

  private val parseOptions   = ConfigParseOptions.defaults().setAllowMissing(false)
  private val resolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)

  private val externalConfigEnvVariable = "DELTA_EXTERNAL_CONF"
  private val externalConfig            = sys.env.get(externalConfigEnvVariable).fold(ConfigFactory.empty()) { p =>
    ConfigFactory.parseFile(new File(p), parseOptions)
  }

  private[plugin] def load(classLoader: ClassLoader, name: String, configFileName: String) = ConfigFactory
    .defaultOverrides()
    .withFallback(externalConfig)
    .withFallback(
      ConfigFactory.parseResources(classLoader, configFileName, parseOptions)
    )
    .resolve(resolveOptions)
    .getConfig(s"plugins.$name")

  implicit val pluginDefOrdering: Ordering[PluginDef] = Ordering.by(_.priority)
}
