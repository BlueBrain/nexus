package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchiveDownload.ArchiveDownloadImpl
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[ArchiveDownload].from[ArchiveDownloadImpl]

  make[Archives].fromEffect {
    (
        projects: Projects,
        archiveDownload: ArchiveDownload,
        cfg: ArchivePluginConfig,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        rcr: RemoteContextResolution @Id("aggregate"),
        clock: Clock[UIO]
    ) =>
      Archives(projects, archiveDownload, cfg)(as, uuidF, rcr, clock)
  }

  many[RemoteContextResolution].addEffect(ContextValue.fromFile("contexts/archives.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.archives -> ctx)
  })

  many[ApiMappings].add(Archives.mappings)

}
