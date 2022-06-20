package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.ArchiveDownload.ArchiveDownloadImpl
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.archive.routes.ArchiveRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[ArchiveDownload].from {
    (
        exchanges: Set[ReferenceExchange],
        aclCheck: AclCheck,
        files: Files,
        sort: JsonKeyOrdering,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate")
    ) =>
      new ArchiveDownloadImpl(exchanges.toList, aclCheck, files)(sort, baseUri, rcr)
  }

  make[Archives].fromEffect {
    (
        projects: Projects,
        archiveDownload: ArchiveDownload,
        cfg: ArchivePluginConfig,
        resourceIdCheck: ResourceIdCheck,
        api: JsonLdApi,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        rcr: RemoteContextResolution @Id("aggregate"),
        clock: Clock[UIO]
    ) =>
      Archives(projects, archiveDownload, cfg, resourceIdCheck)(api, as, uuidF, rcr, clock)
  }

  make[ArchiveRoutes].from {
    (
        archives: Archives,
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        jko: JsonKeyOrdering,
        sc: Scheduler
    ) =>
      new ArchiveRoutes(archives, identities, aclCheck, projects)(baseUri, rcr, jko, sc)
  }

  many[PriorityRoute].add { (cfg: ArchivePluginConfig, routes: ArchiveRoutes) =>
    PriorityRoute(cfg.priority, routes.routes, requiresStrictEntity = true)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/archives-metadata.json"))

  many[RemoteContextResolution].addEffect {
    for {
      ctx     <- ContextValue.fromFile("contexts/archives.json")
      metaCtx <- ContextValue.fromFile("contexts/archives-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.archives         -> ctx,
      contexts.archivesMetadata -> metaCtx
    )

  }

  many[ApiMappings].add(Archives.mappings)

}
