package ai.senscience.nexus.delta

import ai.senscience.nexus.delta.config.{AppConfig, BuildInfo, StrictEntity}
import ai.senscience.nexus.delta.otel.OpenTelemetryInit
import ai.senscience.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import ai.senscience.nexus.delta.plugin.{PluginsLoader, WiringInitializer}
import ai.senscience.nexus.delta.wiring.DeltaModule
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route, RouteResult}
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMonitoring
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOFuture
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.ProjectionConfig.ClusterConfig
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.Locator

import scala.concurrent.duration.DurationInt

object Main extends IOApp {

  private val externalConfigEnvVariable = "DELTA_EXTERNAL_CONF"
  private val pluginEnvVariable         = "DELTA_PLUGINS"
  private val logger                    = Logger[Main.type]
  val pluginsMaxPriority: Int           = 100
  val pluginsMinPriority: Int           = 1

  override def run(args: List[String]): IO[ExitCode] = {
    // TODO: disable this for now, but investigate why it happens
    System.setProperty("cats.effect.logNonDaemonThreadsOnExit", "false")
    val config = sys.env.get(pluginEnvVariable).fold(PluginLoaderConfig())(PluginLoaderConfig(_))
    start(config)
      .use(_ => IO.never)
      .as(ExitCode.Success)
      .redeemWith(logTerminalError, IO.pure)
  }

  private def logTerminalError: Throwable => IO[ExitCode] = e =>
    logger.error(e)("Delta failed to start").as(ExitCode.Error)

  private[delta] def start(loaderConfig: PluginLoaderConfig): Resource[IO, Locator] =
    for {
      _                             <- Resource.eval(logger.info(s"Starting Nexus Delta version '${BuildInfo.version}'."))
      _                             <- Resource.eval(logger.info(s"Loading plugins and config..."))
      (cfg, config, cl, pluginDefs) <- Resource.eval(loadPluginsAndConfig(loaderConfig))
      _                             <- Resource.eval(KamonMonitoring.initialize(config))
      _                             <- OpenTelemetryInit(cfg.description)
      modules                        = DeltaModule(cfg, config, cl)
      (plugins, locator)            <- WiringInitializer(modules, pluginDefs)
      _                             <- bootstrap(locator, plugins)
    } yield locator

  private[delta] def loadPluginsAndConfig(
      config: PluginLoaderConfig
  ): IO[(AppConfig, Config, ClassLoader, List[PluginDef])] =
    for {
      (classLoader, pluginDefs) <- PluginsLoader(config).load
      _                         <- logPlugins(pluginDefs)
      enabledDefs                = pluginDefs.filter(_.enabled)
      _                         <- validatePriority(enabledDefs)
      _                         <- validateDifferentName(enabledDefs)
      configNames                = enabledDefs.map(_.configFileName)
      cfgPathOpt                 = sys.env.get(externalConfigEnvVariable)
      (appConfig, mergedConfig) <- AppConfig.loadOrThrow(cfgPathOpt, configNames, classLoader)
      _                         <- logClusterConfig(appConfig.projections.cluster)
      _                         <- logDatabaseConfig(appConfig.database)
    } yield (appConfig, mergedConfig, classLoader, enabledDefs)

  private def logDatabaseConfig(config: DatabaseConfig) =
    logger.info(s"Database partition strategy is ${config.partitionStrategy}") >>
      logger.info(s"Database config for reads is ${config.read.host} (${config.read.poolSize})") >>
      logger.info(s"Database config for writes is ${config.write.host} (${config.write.poolSize})") >>
      logger.info(s"Database config for streaming is ${config.streaming.host} (${config.streaming.poolSize})")

  private def logClusterConfig(config: ClusterConfig) = {
    if (config.size == 1)
      logger.info(s"Delta is running in standalone mode.")
    else
      logger.info(
        s"Delta is running in clustered mode. The current node is number ${config.nodeIndex} out of a total of ${config.size} nodes."
      )
  }

  private def logPlugins(pluginDefs: List[PluginDef]): IO[Unit] = {
    def pluginLogEntry(pdef: PluginDef): String =
      s"${pdef.info.name} - version: '${pdef.info.version}', enabled: '${pdef.enabled}'"

    if (pluginDefs.isEmpty) logger.info("No plugins discovered.")
    else
      logger.info(s"Discovered plugins: ${pluginDefs.map(p => pluginLogEntry(p)).mkString("\n- ", "\n- ", "")}")
  }

  private def validatePriority(pluginsDef: List[PluginDef]): IO[Unit] =
    IO.raiseWhen(pluginsDef.map(_.priority).distinct.size != pluginsDef.size)(
      PluginInitializationError(
        "Several plugins have the same priority:" + pluginsDef
          .map(p => s"name '${p.info.name}' priority '${p.priority}'")
          .mkString(",")
      )
    ) >>
      (pluginsDef.find(p => p.priority > pluginsMaxPriority || p.priority < pluginsMinPriority) match {
        case Some(pluginDef) =>
          IO.raiseError(
            PluginInitializationError(
              s"Plugin '$pluginDef' has a priority out of the allowed range [$pluginsMinPriority - $pluginsMaxPriority]"
            )
          )
        case None            => IO.unit
      })

  private def validateDifferentName(pluginsDef: List[PluginDef]): IO[Unit] =
    IO.raiseWhen(pluginsDef.map(_.info.name).distinct.size != pluginsDef.size)(
      PluginInitializationError(
        s"Several plugins have the same name: ${pluginsDef.map(p => s"name '${p.info.name}'").mkString(",")}"
      )
    )

  private def routes(locator: Locator, clusterConfig: ClusterConfig): Route = {
    import akka.http.scaladsl.server.Directives.*
    import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.*
    val nodeHeader = RawHeader("X-Delta-Node", clusterConfig.nodeIndex.toString)
    respondWithHeader(nodeHeader) {
      cors(locator.get[CorsSettings]) {
        handleExceptions(locator.get[ExceptionHandler]) {
          handleRejections(locator.get[RejectionHandler]) {
            uriPrefix(locator.get[BaseUri].base) {
              encodeResponse {
                val (strict, rest) = locator.get[Set[PriorityRoute]].partition(_.requiresStrictEntity)
                concat(
                  concat(rest.toVector.sortBy(_.priority).map(_.route)*),
                  locator.get[StrictEntity].apply() {
                    concat(strict.toVector.sortBy(_.priority).map(_.route)*)
                  }
                )
              }
            }
          }
        }
      }
    }
  }

  private def bootstrap(locator: Locator, plugins: List[Plugin]): Resource[IO, Unit] = {
    implicit val as: ActorSystem = locator.get[ActorSystem]
    implicit val cfg: AppConfig  = locator.get[AppConfig]

    val startHttpServer = IOFuture.defaultCancelable(
      IO(
        Http()
          .newServerAt(
            cfg.http.interface,
            cfg.http.port
          )
          .bindFlow(RouteResult.routeToFlow(routes(locator, cfg.projections.cluster)))
      )
    )

    val acquire = {
      for {
        _       <- logger.info("Booting up service....")
        binding <- startHttpServer
        _       <- logger.info(s"Bound to ${binding.localAddress.getHostString}:${binding.localAddress.getPort}")
      } yield ()
    }.recoverWith { th =>
      logger.error(th)(
        s"Failed to perform an http binding on ${cfg.http.interface}:${cfg.http.port}"
      ) >> plugins
        .traverse(_.stop())
        .timeout(30.seconds) >> KamonMonitoring.terminate
    }

    val release = IO.fromFuture(IO(as.terminate()))

    Resource.make(acquire)(_ => release.void)
  }
}
