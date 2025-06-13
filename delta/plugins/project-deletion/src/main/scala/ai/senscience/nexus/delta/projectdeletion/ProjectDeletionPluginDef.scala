package ai.senscience.nexus.delta.projectdeletion

import ai.senscience.nexus.delta.plugins.projectdeletion.BuildInfo
import ai.senscience.nexus.delta.projectdeletion.model.ProjectDeletionConfig
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import pureconfig.ConfigSource

class ProjectDeletionPluginDef extends PluginDef {

  /**
    * Distage module definition for this plugin.
    */
  override def module: ModuleDef = new ModuleDef {
    make[ProjectDeletionConfig].fromEffect {
      IO.delay {
        ConfigSource
          .fromConfig(pluginConfigObject)
          .loadOrThrow[ProjectDeletionConfig]
      }
    }
    include(new ProjectDeletionModule(priority))
  }

  /**
    * Plugin description
    */
  override def info: PluginDescription =
    PluginDescription("project-deletion", BuildInfo.version)

  /**
    * Initialize the plugin.
    *
    * @param locator
    *   distage dependencies [[Locator]]
    * @return
    *   [[Plugin]] instance.
    */
  override def initialize(locator: Locator): IO[Plugin] =
    IO.pure(ProjectDeletionPlugin)
}
