package ch.epfl.bluebrain.nexus.ship

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.files.FileProcessor
import ch.epfl.bluebrain.nexus.ship.organizations.OrganizationProvider
import ch.epfl.bluebrain.nexus.ship.projects.{OriginalProjectContext, ProjectProcessor}
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverProcessor
import ch.epfl.bluebrain.nexus.ship.resources.{ResourceProcessor, ResourceWiring, SourcePatcher}
import ch.epfl.bluebrain.nexus.ship.schemas.{SchemaProcessor, SchemaWiring}
import ch.epfl.bluebrain.nexus.ship.views.{BlazegraphViewProcessor, CompositeViewProcessor, ElasticSearchViewProcessor, ViewPatcher}
import fs2.Stream

object RunShip {

  def apply(
      eventsStream: Stream[IO, RowEvent],
      s3Client: S3StorageClient,
      config: InputConfig,
      xas: Transactors
  ): IO[ImportReport] = {
    val clock                         = Clock[IO]
    val uuidF                         = UUIDF.random
    // Resources may have been created with different configurations so we adopt the lenient one for the import
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    for {
      report <- {
        val orgProvider                     =
          OrganizationProvider(config.eventLog, config.serviceAccount.value, xas, clock)(uuidF)
        val fetchContext                    = FetchContext(ApiMappings.empty, xas, Quotas.disabled)
        val originalProjectContext          = new OriginalProjectContext(xas)
        val eventLogConfig                  = config.eventLog
        val originalBaseUri                 = config.originalBaseUri
        val targetBaseUri                   = config.targetBaseUri
        val projectMapper                   = ProjectMapper(config.projectMapping)
        implicit val iriPatcher: IriPatcher = IriPatcher(config.iriPatcher, config.projectMapping)
        for {
          // Provision organizations
          _                           <- orgProvider.create(config.organizations.values)
          fetchActiveOrg               = FetchActiveOrganization(xas)
                    // format: off
                    // Wiring
                    eventClock                  <- EventClock.init()
                    remoteContextResolution     <- ContextWiring.remoteContextResolution
                    (schemaLog, fetchSchema)     = SchemaWiring(config.eventLog, eventClock, xas)
                    (resourceLog, fetchResource) = ResourceWiring(fetchSchema, config, eventClock, xas)
                    rcr                          = ContextWiring.resolverContextResolution(fetchResource, fetchContext, remoteContextResolution, eventLogConfig, eventClock, xas)
                    schemaImports                = SchemaWiring.schemaImports(fetchResource, fetchSchema, fetchContext, eventLogConfig, eventClock, xas)
                    // Processors
          fileSelf                     = FileSelf(originalProjectContext)(originalBaseUri)
          sourcePatcher                = SourcePatcher(fileSelf, projectMapper, iriPatcher, targetBaseUri, eventClock, xas, config)
          projectProcessor            <- ProjectProcessor(fetchActiveOrg, fetchContext, rcr, originalProjectContext, projectMapper, iriPatcher, config, eventClock, xas)(targetBaseUri, jsonLdApi)
          resolverProcessor            = ResolverProcessor(fetchContext, projectMapper, iriPatcher, eventLogConfig, eventClock, xas)
          schemaProcessor              = SchemaProcessor(schemaLog, fetchContext, schemaImports, rcr, projectMapper, sourcePatcher, eventClock)
                    resourceProcessor            = ResourceProcessor(resourceLog, rcr, projectMapper, fetchContext, sourcePatcher, iriPatcher, config.resourceTypesToIgnore, eventClock)
                    viewPatcher                  = new ViewPatcher(projectMapper, iriPatcher)
                    esViewsProcessor             = ElasticSearchViewProcessor(fetchContext, rcr, projectMapper, viewPatcher, eventLogConfig, eventClock, xas)
                    bgViewsProcessor             = BlazegraphViewProcessor(fetchContext, rcr, projectMapper, viewPatcher, eventLogConfig, eventClock, xas)
                    compositeViewsProcessor      = CompositeViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    fileProcessor                = FileProcessor(fetchContext, s3Client, projectMapper, rcr, config, eventClock, xas)
                    // format: on
          _                           <- logger.info("Starting import")
          droppedEventStore            = new DroppedEventStore(xas)
          report                      <- EventProcessor
                                           .run(
                                             eventsStream,
                                             droppedEventStore,
                                             projectProcessor,
                                             resolverProcessor,
                                             schemaProcessor,
                                             resourceProcessor,
                                             esViewsProcessor,
                                             bgViewsProcessor,
                                             compositeViewsProcessor,
                                             fileProcessor
                                           )
          _                           <- logger.info(s"Import finished.")
        } yield report
      }
    } yield report
  }

  private val logger = Logger[RunShip.type]

}
