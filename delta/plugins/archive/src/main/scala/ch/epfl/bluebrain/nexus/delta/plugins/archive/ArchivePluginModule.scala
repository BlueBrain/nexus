package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchiveDownload.ArchiveDownloadImpl
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

  make[ArchiveDownload].from[ArchiveDownloadImpl]

  make[Archives].fromEffect {
    (
        projects: Projects,
        archiveDownload: ArchiveDownload,
        cfg: ArchivePluginConfig,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        rcr: RemoteContextResolution,
        clock: Clock[UIO]
    ) =>
      Archives(projects, archiveDownload, cfg)(as, uuidF, rcr, clock)
  }

  make[ArchivePlugin]

}
