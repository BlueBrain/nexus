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
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import ch.epfl.bluebrain.nexus.ship.model.InputEvent
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

  def run(file: Path, config: Option[Path]): IO[ImportReport] = {
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
                  for {
                    // Provision organizations
                    _                           <- orgProvider.create(config.organizations.values)
                    events                       = eventStream(file)
                    fetchActiveOrg               = FetchActiveOrganization(xas)
                    // Wiring
                    eventClock                  <- EventClock.init()
                    (schemaLog, fetchSchema)    <- SchemaWiring(config.eventLog, eventClock, xas, jsonLdApi)
                    (resourceLog, fetchResource) =
                      ResourceWiring(fetchContext, fetchSchema, eventLogConfig, eventClock, xas)
                    rcr                         <- ContextWiring
                                                     .resolverContextResolution(fetchResource, fetchContext, eventLogConfig, eventClock, xas)
                    schemaImports                = SchemaWiring.schemaImports(
                                                     fetchResource,
                                                     fetchSchema,
                                                     fetchContext,
                                                     eventLogConfig,
                                                     eventClock,
                                                     xas
                                                   )
                    // Processors
                    projectProcessor            <- ProjectProcessor(fetchActiveOrg, eventLogConfig, eventClock, xas)(baseUri)
                    resolverProcessor            = ResolverProcessor(fetchContext, eventLogConfig, eventClock, xas)
                    schemaProcessor              = SchemaProcessor(schemaLog, fetchContext, schemaImports, rcr, eventClock)
                    resourceProcessor            = ResourceProcessor(resourceLog, fetchContext, eventClock)
                    esViewsProcessor            <- ElasticSearchViewProcessor(fetchContext, rcr, eventLogConfig, eventClock, xas)
                    bgViewsProcessor             = BlazegraphViewProcessor(fetchContext, rcr, eventLogConfig, eventClock, xas)
                    compositeViewsProcessor      = CompositeViewProcessor(fetchContext, rcr, eventLogConfig, eventClock, xas)
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

  private def eventStream(file: Path): Stream[IO, InputEvent] =
    Files[IO].readUtf8Lines(file).zipWithIndex.evalMap { case (line, index) =>
      IO.fromEither(decode[InputEvent](line)).onError { err =>
        logger.error(err)(s"Error parsing to event at line $index")
      }
    }

}
