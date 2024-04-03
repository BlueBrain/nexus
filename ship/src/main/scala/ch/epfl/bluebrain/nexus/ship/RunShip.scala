package ch.epfl.bluebrain.nexus.ship

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import ch.epfl.bluebrain.nexus.ship.organizations.OrganizationProvider
import ch.epfl.bluebrain.nexus.ship.projects.ProjectProcessor
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverProcessor
import ch.epfl.bluebrain.nexus.ship.resources.{ResourceProcessor, ResourceWiring}
import ch.epfl.bluebrain.nexus.ship.schemas.{SchemaProcessor, SchemaWiring}
import ch.epfl.bluebrain.nexus.ship.views.{BlazegraphViewProcessor, CompositeViewProcessor, ElasticSearchViewProcessor}
import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.parser.decode

class RunShip {

  private val logger = Logger[RunShip]

  def run(file: Path, config: Option[Path], fromOffset: Offset = Offset.start): IO[ImportReport] = {
    val clock                         = Clock[IO]
    val uuidF                         = UUIDF.random
    // Resources may have been created with different configurations so we adopt the lenient one for the import
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    for {
      _      <- logger.info(s"Running the import with file $file, config $config")
      config <- ShipConfig.load(config)
      report <- Transactors.init(config.database).use { xas =>
                  val orgProvider    =
                    OrganizationProvider(config.eventLog, config.serviceAccount.value, xas, clock)(uuidF)
                  val fetchContext   = FetchContext(ApiMappings.empty, xas, Quotas.disabled)
                  val eventLogConfig = config.eventLog
                  val baseUri        = config.baseUri
                  val projectMapper  = ProjectMapper(config.projectMapping)
                  for {
                    // Provision organizations
                    _                           <- orgProvider.create(config.organizations.values)
                    events                       = eventStream(file, fromOffset)
                    fetchActiveOrg               = FetchActiveOrganization(xas)
                    // format: off
                    // Wiring
                    eventClock                  <- EventClock.init()
                    remoteContextResolution     <- ContextWiring.remoteContextResolution
                    (schemaLog, fetchSchema)     = SchemaWiring(config.eventLog, eventClock, xas)
                    (resourceLog, fetchResource) = ResourceWiring(fetchContext, fetchSchema, remoteContextResolution, eventLogConfig, eventClock, xas)
                    rcr                          = ContextWiring.resolverContextResolution(fetchResource, fetchContext, remoteContextResolution, eventLogConfig, eventClock, xas)
                    schemaImports                = SchemaWiring.schemaImports(fetchResource, fetchSchema, fetchContext, eventLogConfig, eventClock, xas)
                    // Processors
                    projectProcessor            <- ProjectProcessor(fetchActiveOrg, projectMapper, eventLogConfig, eventClock, xas)(baseUri)
                    resolverProcessor            = ResolverProcessor(fetchContext, projectMapper, eventLogConfig, eventClock, xas)
                    schemaProcessor              = SchemaProcessor(schemaLog, fetchContext, schemaImports, rcr, projectMapper, eventClock)
                    resourceProcessor            = ResourceProcessor(resourceLog, rcr, projectMapper, fetchContext, eventClock)
                    esViewsProcessor            <- ElasticSearchViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    bgViewsProcessor             = BlazegraphViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    compositeViewsProcessor      = CompositeViewProcessor(fetchContext, rcr, projectMapper, eventLogConfig, eventClock, xas)
                    // format: on
                    report                      <- EventProcessor
                                                     .run(
                                                       events,
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

  private def eventStream(file: Path, fromOffset: Offset): Stream[IO, RowEvent] =
    Files[IO]
      .readUtf8Lines(file)
      .zipWithIndex
      .evalMap { case (line, index) =>
        IO.fromEither(decode[RowEvent](line)).onError { err =>
          logger.error(err)(s"Error parsing to event at line $index")
        }
      }
      .filter { event =>
        event.ordering.value >= fromOffset.value
      }

}
