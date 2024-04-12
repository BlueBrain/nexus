package ch.epfl.bluebrain.nexus.ship

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidateShacl
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
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import fs2.io.file.Path

trait RunShip {

  private val logger = Logger[RunShip]

  def loadConfig(config: Option[Path]): IO[ShipConfig]

  def eventsStream(path: Path, fromOffset: Offset): Stream[IO, RowEvent]

  def run(path: Path, config: Option[Path], fromOffset: Offset = Offset.start): IO[ImportReport] = {
    val clock                         = Clock[IO]
    val uuidF                         = UUIDF.random
    // Resources may have been created with different configurations so we adopt the lenient one for the import
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    for {
      _      <- logger.info(s"Running the import with file $path, config $config")
      config <- loadConfig(config)
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
                                                       eventsStream(path, fromOffset),
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

object RunShip {

  def localShip = new RunShip {
    override def loadConfig(config: Option[Path]): IO[ShipConfig] =
      ShipConfig.load(config)

    override def eventsStream(path: Path, fromOffset: Offset): Stream[IO, RowEvent] =
      EventStreamer.localStreamer.stream(path, fromOffset)
  }

  def s3Ship(client: S3StorageClient, bucket: BucketName) = new RunShip {
    override def loadConfig(config: Option[Path]): IO[ShipConfig] = config match {
      case Some(configPath) =>
        val configStream = client.readFile(bucket, FileKey(NonEmptyString.unsafeFrom(configPath.toString)))
        ShipConfig.load(configStream)
      case None             => ShipConfig.load(None)
    }

    override def eventsStream(path: Path, fromOffset: Offset): Stream[IO, RowEvent] =
      EventStreamer
        .s3eventStreamer(client, bucket)
        .stream(path, fromOffset)
  }

}
