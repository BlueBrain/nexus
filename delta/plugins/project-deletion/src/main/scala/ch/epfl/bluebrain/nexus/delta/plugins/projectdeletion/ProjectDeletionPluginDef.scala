package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.{Task, UIO}
import pureconfig.ConfigSource

class ProjectDeletionPluginDef extends PluginDef {

  /**
    * Distage module definition for this plugin.
    */
  override def module: ModuleDef = new ModuleDef {
    make[ProjectDeletionConfig].fromEffect {
      UIO.delay {
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
    PluginDescription(Name.unsafe("project-deletion"), BuildInfo.version)

  /**
    * Initialize the plugin.
    *
    * @param locator
    *   distage dependencies [[Locator]]
    * @return
    *   [[Plugin]] instance.
    */
  override def initialize(locator: Locator): Task[Plugin] =
    Task.delay(locator.get[Plugin])
}
