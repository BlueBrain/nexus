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
import ch.epfl.bluebrain.nexus.ship.resources.ResourceProcessor
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
                    _                 <- orgProvider.create(config.organizations.values)
                    events             = eventStream(file)
                    fetchActiveOrg     = FetchActiveOrganization(xas)
                    projectProcessor  <- ProjectProcessor(fetchActiveOrg, eventLogConfig, xas)(baseUri)
                    resolverProcessor <- ResolverProcessor(fetchContext, eventLogConfig, xas)
                    resourceProcessor <- ResourceProcessor(eventLogConfig, fetchContext, xas)
                    report            <- EventProcessor.run(events, projectProcessor, resolverProcessor, resourceProcessor)
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
