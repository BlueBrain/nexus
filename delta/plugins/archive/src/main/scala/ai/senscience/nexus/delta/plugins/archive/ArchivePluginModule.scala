package ai.senscience.nexus.delta.plugins.archive

import ai.senscience.nexus.delta.plugins.archive.model.contexts
import ai.senscience.nexus.delta.plugins.archive.routes.ArchiveRoutes
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Archive plugin wiring.
  */
object ArchivePluginModule extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[ArchivePluginConfig].fromEffect { cfg: Config => ArchivePluginConfig.load(cfg) }

  make[ArchiveDownload].from {
    (
        aclCheck: AclCheck,
        shifts: ResourceShifts,
        files: Files,
        fileSelf: FileSelf,
        sort: JsonKeyOrdering,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate")
    ) =>
      ArchiveDownload(aclCheck, shifts, files, fileSelf)(sort, baseUri, rcr)
  }

  make[FileSelf].from { (fetchContext: FetchContext, baseUri: BaseUri) =>
    FileSelf(fetchContext)(baseUri)
  }

  make[Archives].from {
    (
        fetchContext: FetchContext,
        archiveDownload: ArchiveDownload,
        cfg: ArchivePluginConfig,
        xas: Transactors,
        uuidF: UUIDF,
        rcr: RemoteContextResolution @Id("aggregate"),
        clock: Clock[IO]
    ) =>
      Archives(fetchContext, archiveDownload, cfg, xas, clock)(uuidF, rcr)
  }

  make[ArchiveRoutes].from {
    (
        archives: Archives,
        identities: Identities,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        jko: JsonKeyOrdering
    ) =>
      new ArchiveRoutes(archives, identities, aclCheck)(baseUri, rcr, jko)
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
