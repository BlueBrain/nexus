package ch.epfl.bluebrain.nexus.delta.plugins.archive

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.archive.routes.ArchiveRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegmentRef, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
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
        aclCheck: AclCheck,
        shifts: ResourceShifts,
        files: Files,
        sort: JsonKeyOrdering,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        resolveSelf: ResolveFileSelf
    ) =>
      ArchiveDownload(
        aclCheck,
        shifts.fetch,
        (id: ResourceRef, project: ProjectRef, caller: Caller) => files.fetchContent(IdSegmentRef(id), project)(caller),
        resolveSelf
      )(sort, baseUri, rcr)
  }

  make[ResolveFileSelf].from {
    (fetchContext: FetchContext[ContextRejection], baseUri: BaseUri) =>
      ResolveFileSelf(fetchContext.mapRejection(ProjectContextRejection))(baseUri)
  }

  make[Archives].from {
    (
        fetchContext: FetchContext[ContextRejection],
        archiveDownload: ArchiveDownload,
        cfg: ArchivePluginConfig,
        xas: Transactors,
        api: JsonLdApi,
        uuidF: UUIDF,
        rcr: RemoteContextResolution @Id("aggregate"),
        clock: Clock[UIO]
    ) =>
      Archives(fetchContext.mapRejection(ProjectContextRejection), archiveDownload, cfg, xas)(
        api,
        uuidF,
        rcr,
        clock
      )
  }

  make[ArchiveRoutes].from {
    (
        archives: Archives,
        identities: Identities,
        aclCheck: AclCheck,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        jko: JsonKeyOrdering,
        sc: Scheduler
    ) =>
      new ArchiveRoutes(archives, identities, aclCheck, schemeDirectives)(baseUri, rcr, jko, sc)
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
