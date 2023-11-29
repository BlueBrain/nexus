package ch.epfl.bluebrain.nexus.delta

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem => ActorSystemClassic}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route, RouteResult}
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, BuildInfo}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMonitoring
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.fromFutureLegacy
import ch.epfl.bluebrain.nexus.delta.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.plugin.{PluginsLoader, WiringInitializer}
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.http.StrictEntity
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import ch.epfl.bluebrain.nexus.delta.wiring.DeltaModule
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
    } yield (appConfig, mergedConfig, classLoader, enabledDefs)

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

  private def routes(locator: Locator): Route = {
    import akka.http.scaladsl.server.Directives._
    import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives._
    cors(locator.get[CorsSettings]) {
      handleExceptions(locator.get[ExceptionHandler]) {
        handleRejections(locator.get[RejectionHandler]) {
          uriPrefix(locator.get[BaseUri].base) {
            encodeResponse {
              val (strict, rest) = locator.get[Set[PriorityRoute]].partition(_.requiresStrictEntity)
              concat(
                concat(rest.toVector.sortBy(_.priority).map(_.route): _*),
                locator.get[StrictEntity].apply() {
                  concat(strict.toVector.sortBy(_.priority).map(_.route): _*)
                }
              )
            }
          }
        }
      }
    }
  }

  private def bootstrap(locator: Locator, plugins: List[Plugin]): Resource[IO, Unit] = {
    implicit val as: ActorSystemClassic = locator.get[ActorSystem[Nothing]].toClassic
    implicit val cfg: AppConfig         = locator.get[AppConfig]

    val startHttpServer = fromFutureLegacy(
      IO(
        Http()
          .newServerAt(
            cfg.http.interface,
            cfg.http.port
          )
          .bindFlow(RouteResult.routeToFlow(routes(locator)))
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
