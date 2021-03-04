package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import com.typesafe.config.Config
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[Archives].fromEffect {
    (
        projects: Projects,
        cfg: ArchivePluginConfig,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        rcr: RemoteContextResolution,
        clock: Clock[UIO]
    ) =>
      Archives(projects, cfg)(as, uuidF, rcr, clock)
  }

  // TODO: update this when the routes are defined
  make[ArchivePlugin]

}
