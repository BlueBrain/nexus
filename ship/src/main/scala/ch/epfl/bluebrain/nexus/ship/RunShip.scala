package ch.epfl.bluebrain.nexus.ship

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidateShacl
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.organizations.OrganizationProvider
import ch.epfl.bluebrain.nexus.ship.projects.ProjectProcessor
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverProcessor
import ch.epfl.bluebrain.nexus.ship.resources.{ResourceProcessor, ResourceWiring}
import ch.epfl.bluebrain.nexus.ship.schemas.{SchemaProcessor, SchemaWiring}
import ch.epfl.bluebrain.nexus.ship.views.{BlazegraphViewProcessor, CompositeViewProcessor, ElasticSearchViewProcessor}
import fs2.Stream

object RunShip {

  def apply(eventsStream: Stream[IO, RowEvent], config: InputConfig, xas: Transactors): IO[ImportReport] = {
    val clock                         = Clock[IO]
    val uuidF                         = UUIDF.random
    // Resources may have been created with different configurations so we adopt the lenient one for the import
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    for {
      report <- {
        val orgProvider    =
          OrganizationProvider(config.eventLog, config.serviceAccount.value, xas, clock)(uuidF)
        val fetchContext   = FetchContext(ApiMappings.empty, xas, Quotas.disabled)
        val eventLogConfig = config.eventLog
        val baseUri        = config.baseUri
        val projectMapper  = ProjectMapper(config.projectMapping)
        for {
          // Provision organizations
          _                           <- orgProvider.create(config.organizations.values)
          fetchActiveOrg               = FetchActiveOrganization(xas)
                    // format: off
                    // Wiring
                    eventClock                  <- EventClock.init()
                    remoteContextResolution     <- ContextWiring.remoteContextResolution
                    validateShacl               <- ValidateShacl(remoteContextResolution)
                    (schemaLog, fetchSchema)     = SchemaWiring(config.eventLog, eventClock, xas)
                    (resourceLog, fetchResource) = ResourceWiring(fetchContext, fetchSchema, validateShacl, eventLogConfig, eventClock, xas)
                    rcr                          = ContextWiring.resolverContextResolution(fetchResource, fetchContext, remoteContextResolution, eventLogConfig, eventClock, xas)
                    schemaImports                = SchemaWiring.schemaImports(fetchResource, fetchSchema, fetchContext, eventLogConfig, eventClock, xas)
                    // Processors
                    projectProcessor            <- ProjectProcessor(fetchActiveOrg, fetchContext, rcr, projectMapper, config, eventClock, xas)(baseUri, jsonLdApi)
                    resolverProcessor            = ResolverProcessor(fetchContext, projectMapper, eventLogConfig, eventClock, xas)
                    schemaProcessor              = SchemaProcessor(schemaLog, fetchContext, schemaImports, rcr, projectMapper, eventClock)
                    resourceProcessor            = ResourceProcessor(resourceLog, rcr, projectMapper, fetchContext, eventClock)
                    esViewsProcessor             = ElasticSearchViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    bgViewsProcessor             = BlazegraphViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    compositeViewsProcessor      = CompositeViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    // format: on
          report                      <- EventProcessor
                                           .run(
                                             eventsStream,
                                             projectProcessor,
                                             resolverProcessor,
                                             schemaProcessor,
                                             resourceProcessor,
                                             esViewsProcessor,
                                             bgViewsProcessor,
                                             compositeViewsProcessor
                                           )
        } yield report
      }
    } yield report
  }

}
