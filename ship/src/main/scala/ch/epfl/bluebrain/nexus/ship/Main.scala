package ch.epfl.bluebrain.nexus.ship

import cats.effect.{Clock, ExitCode, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.ship.BuildInfo
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import ch.epfl.bluebrain.nexus.ship.model.InputEvent
import ch.epfl.bluebrain.nexus.ship.organizations.OrganizationProvider
import ch.epfl.bluebrain.nexus.ship.projects.ProjectProcessor
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverProcessor
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.parser._

object Main
    extends CommandIOApp(
      name = "ship",
      header = "Nexus Ship",
      version = BuildInfo.version
    ) {

  private val logger = Logger[Main.type]

  private val inputFile: Opts[Path] =
    Opts.option[String]("file", help = "The data file containing the imports.").map(Path(_))

  private val configFile: Opts[Option[Path]] =
    Opts.option[String]("config", help = "The configuration file.").map(Path(_)).orNone

  private val offset: Opts[Offset] = Opts
    .option[Long]("offset", help = "To perform an incremental import from the provided offset.")
    .map(Offset.at)
    .withDefault(Offset.start)

  private val run = Opts.subcommand("run", "Run an import") {
    (inputFile, configFile, offset).mapN(Run)
  }

  private val showConfig = Opts.subcommand("config", "Show reconciled config") {
    configFile.map(ShowConfig)
  }

  override def main: Opts[IO[ExitCode]] =
    (run orElse showConfig)
      .map {
        case Run(file, config, _) => run(file, config)
        case ShowConfig(config)   => showConfig(config)
      }
      .map(_.as(ExitCode.Success))

  private[ship] def run(file: Path, config: Option[Path]): IO[Unit] = {
    val clock                         = Clock[IO]
    val uuidF                         = UUIDF.random
    // Resources may have been created with different configurations so we adopt the lenient one for the import
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    for {
      _      <- logger.info(s"Running the import with file $file, config $config and from offset $offset")
      config <- ShipConfig.load(config)
      _      <- Transactors.init(config.database).use { xas =>
                  val orgProvider    = OrganizationProvider(config.eventLog, config.serviceAccount.value, xas, clock)(uuidF)
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
                    _                 <- EventProcessor.run(events, projectProcessor, resolverProcessor)
                  } yield ()
                }
    } yield ()
  }

  private[ship] def showConfig(config: Option[Path]) =
    for {
      _      <- logger.info(s"Showing reconciled config")
      config <- ShipConfig.merge(config).map(_._2)
      _      <- logger.info(config.root().render())
    } yield ()

  private def eventStream(file: Path): Stream[IO, InputEvent] =
    Files[IO].readUtf8Lines(file).zipWithIndex.evalMap { case (line, index) =>
      IO.fromEither(decode[InputEvent](line)).onError { err =>
        logger.error(err)(s"Error parsing to event at line $index")
      }
    }

  sealed private trait Command

  final private case class Run(file: Path, config: Option[Path], offset: Offset) extends Command

  final private case class ShowConfig(config: Option[Path]) extends Command

}
