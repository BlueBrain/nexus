package ch.epfl.bluebrain.nexus.delta.plugins.archive

import cats.effect.{Clock, IO}
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import cats.effect.unsafe.IORuntime

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[ArchiveDownload].from {
    (
        aclCheck: AclCheck,
        shifts: ResourceShifts,
        files: Files,
        fileSelf: FileSelf,
        sort: JsonKeyOrdering,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        runtime: IORuntime
    ) =>
      ArchiveDownload(aclCheck, shifts, files, fileSelf)(sort, baseUri, rcr, runtime)
  }

  make[FileSelf].from { (fetchContext: FetchContext[ContextRejection], baseUri: BaseUri) =>
    FileSelf(fetchContext.mapRejection(ProjectContextRejection))(baseUri)
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
        clock: Clock[IO]
    ) =>
      Archives(fetchContext.mapRejection(ProjectContextRejection), archiveDownload, cfg, xas, clock)(
        api,
        uuidF,
        rcr
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
        runtime: IORuntime
    ) =>
      new ArchiveRoutes(archives, identities, aclCheck, schemeDirectives)(baseUri, rcr, jko, runtime)
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
