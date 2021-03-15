package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchiveDownload.ArchiveDownloadImpl
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Projects, ReferenceExchange}
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[ArchiveDownload].from {
    (
        exchanges: Set[ReferenceExchange],
        acls: Acls,
        files: Files,
        sort: JsonKeyOrdering,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate")
    ) =>
      new ArchiveDownloadImpl(exchanges, acls, files)(sort, baseUri, rcr)
  }

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
