package ch.epfl.bluebrain.nexus.delta

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem => ActorSystemClassic}
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route, RouteResult}
import cats.effect.ExitCode
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, BuildInfo}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMonitoring
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.migration.Migration
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.service.plugin.{PluginsLoader, WiringInitializer}
import ch.epfl.bluebrain.nexus.delta.wiring.DeltaModule
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import com.typesafe.scalalogging.{Logger => Logging}
import izumi.distage.model.Locator
import monix.bio.{BIOApp, IO, Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.error.ConfigReaderFailures

import scala.concurrent.duration.DurationInt

object Main extends BIOApp {

  private val externalConfigEnvVariable = "DELTA_EXTERNAL_CONF"
  private val pluginEnvVariable         = "DELTA_PLUGINS"
  private val log: Logging              = Logging[Main.type]
  val pluginsMaxPriority: Int           = 100
  val pluginsMinPriority: Int           = 1

  override def run(args: List[String]): UIO[ExitCode] = {
    LoggerFactory.getLogger("Main") // initialize logging to suppress SLF4J error
    // TODO: disable this for now, but investigate why it happens
    System.setProperty("cats.effect.logNonDaemonThreadsOnExit", "false")
    val config = sys.env.get(pluginEnvVariable).fold(PluginLoaderConfig())(PluginLoaderConfig(_))
    (start(_ => Task.unit, config) >> UIO.never).as(ExitCode.Success).attempt.map(_.fold(identity, identity))
  }

  private[delta] def start(preStart: Locator => Task[Unit], loaderConfig: PluginLoaderConfig): IO[ExitCode, Unit] =
    for {
      _                             <- UIO.delay(log.info(s"Starting Nexus Delta version '${BuildInfo.version}'."))
      (cfg, config, cl, pluginDefs) <- loadPluginsAndConfig(loaderConfig)
      _                             <- KamonMonitoring.initialize(config)
      modules                       <- UIO.delay(DeltaModule(cfg, config, cl))
      (plugins, locator)            <- WiringInitializer(modules, pluginDefs).handleError
      _                             <- preStart(locator).handleError
      _                             <- bootstrap(locator, plugins).handleError
    } yield ()

  private[delta] def loadPluginsAndConfig(
      config: PluginLoaderConfig
  ): IO[ExitCode, (AppConfig, Config, ClassLoader, List[PluginDef])] =
    for {
      (classLoader, pluginDefs) <- PluginsLoader(config).load.handleError
      _                         <- logPlugins(pluginDefs)
      enabledDefs                = pluginDefs.filter(_.enabled)
      _                         <- validatePriority(enabledDefs)
      _                         <- validateDifferentName(enabledDefs)
      configNames                = enabledDefs.map(_.configFileName)
      (appConfig, mergedConfig) <-
        AppConfig.load(sys.env.get(externalConfigEnvVariable), configNames, classLoader).handleError
    } yield (appConfig, mergedConfig, classLoader, enabledDefs)

  private def logPlugins(pluginDefs: List[PluginDef]): UIO[Unit] = {
    def pluginLogEntry(pdef: PluginDef): String =
      s"${pdef.info.name} - version: '${pdef.info.version}', enabled: '${pdef.enabled}'"

    if (pluginDefs.isEmpty) UIO.delay(log.info("No plugins discovered."))
    else
      UIO.delay {
        log.info(s"Discovered plugins: ${pluginDefs.map(p => pluginLogEntry(p)).mkString("\n- ", "\n- ", "")}")
      }
  }

  private def validatePriority(pluginsDef: List[PluginDef]): IO[ExitCode, Unit] =
    if (pluginsDef.map(_.priority).distinct.size != pluginsDef.size)
      UIO.delay(
        log.error(
          "Several plugins have the same priority:" +
            pluginsDef.map(p => s"name '${p.info.name}' priority '${p.priority}'").mkString(",")
        )
      ) >> IO.raiseError(ExitCode.Error)
    else
      pluginsDef.find(p => p.priority > pluginsMaxPriority || p.priority < pluginsMinPriority) match {
        case Some(pluginDef) =>
          UIO.delay(
            log.error(
              s"Plugin '$pluginDef' has a priority out of the allowed range [$pluginsMinPriority - $pluginsMaxPriority]"
            )
          ) >>
            IO.raiseError(ExitCode.Error)
        case None            => IO.unit
      }

  private def validateDifferentName(pluginsDef: List[PluginDef]): IO[ExitCode, Unit] =
    if (pluginsDef.map(_.info.name).distinct.size == pluginsDef.size) IO.unit
    else
      UIO.delay(
        log.error(
          s"Several plugins have the same name: ${pluginsDef.map(p => s"name '${p.info.name}'").mkString(",")}"
        )
      ) >> IO.raiseError(ExitCode.Error)

  private def routes(locator: Locator): Route = {
    import akka.http.scaladsl.server.Directives._
    import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives._
    cors(locator.get[CorsSettings]) {
      handleExceptions(locator.get[ExceptionHandler]) {
        handleRejections(locator.get[RejectionHandler]) {
          uriPrefix(locator.get[BaseUri].base) {
            encodeResponse {
              concat(locator.get[Vector[Route]]: _*)
            }
          }
        }
      }
    }
  }

  private def bootstrap(locator: Locator, plugins: List[Plugin]): Task[Unit] =
    Task.delay {
      implicit val as: ActorSystemClassic = locator.get[ActorSystem[Nothing]].toClassic
      implicit val scheduler: Scheduler   = locator.get[Scheduler]
      implicit val cfg: AppConfig         = locator.get[AppConfig]
      val logger                          = locator.get[Logger]
      val cluster                         = Cluster(as)

      sys.env.get("MIGRATION_1_7").foreach { _ =>
        locator.get[Migration].run.runSyncUnsafe()
        RepairTagViews.repair
      }

      sys.env.get("DELETE_PERSISTENCE_IDS").foreach { persistenceIds =>
        DeletePersistenceIds.delete(persistenceIds.split(",").toSeq)
      }

      if (sys.env.getOrElse("REPAIR_FROM_MESSAGES", "false").toBoolean) {
        RepairTagViews.repair
      }

      logger.info("Booting up service....")

      val binding = Task
        .fromFutureLike(
          Task.delay(
            Http()
              .newServerAt(
                cfg.http.interface,
                cfg.http.port
              )
              .bindFlow(RouteResult.routeToFlow(routes(locator)))
          )
        )
        .flatMap { binding =>
          Task.delay(logger.infoN("Bound to {}:{}", binding.localAddress.getHostString, binding.localAddress.getPort))
        }
        .onErrorRecoverWith { th =>
          Task.delay(
            logger.error(
              s"Failed to perform an http binding on ${cfg.http.interface}:${cfg.http.port}",
              th
            )
          ) >> Task
            .traverse(plugins)(_.stop())
            .timeout(30.seconds) >> KamonMonitoring.terminate >> terminateActorSystem()
        }

      cluster.registerOnMemberUp {
        logger.info(" === Cluster is LIVE === ")
        binding.runAsyncAndForget
      }

      cluster.joinSeedNodes(cfg.cluster.seedList)
    }

  private def terminateActorSystem()(implicit as: ActorSystemClassic): Task[Unit] =
    Task.deferFuture(as.terminate()).timeout(15.seconds) >> Task.unit

  implicit private def configReaderErrorHandler(failures: ConfigReaderFailures): UIO[ExitCode] = {
    val lines =
      "The application configuration failed to load, due to:" ::
        failures.toList
          .flatMap { f =>
            f.origin match {
              case Some(o) =>
                val file = Option(o.filename()) orElse Option(o.url()).map(_.toString) orElse Option(o.resource())
                file match {
                  case Some(path) => f.description :: s"  file: $path" :: s"  line: ${o.lineNumber}" :: Nil
                  case None       => f.description :: Nil
                }
              case None    => f.description :: Nil
            }
          }
    UIO.delay(lines.foreach(log.error(_))) >> UIO.pure(ExitCode.Error)
  }

  implicit private def pluginErrorHandler(error: PluginError): UIO[ExitCode] =
    UIO.delay(log.error(s"A plugin failed to be loaded due to: '${error.getMessage}'")) >> UIO.pure(ExitCode.Error)

  implicit private def unexpectedErrorHandler(error: Throwable): UIO[ExitCode] =
    UIO.delay(log.error(s"A plugin failed  due to: '${error.getMessage}'")) >> UIO.pure(ExitCode.Error)

  implicit class IOHandleErrorSyntax[E, A](private val io: IO[E, A]) extends AnyVal {
    def handleError(implicit f: E => UIO[ExitCode]): IO[ExitCode, A] =
      io.attempt.flatMap {
        case Left(value)  => f(value).flip
        case Right(value) => IO.pure(value)
      }
  }
}
