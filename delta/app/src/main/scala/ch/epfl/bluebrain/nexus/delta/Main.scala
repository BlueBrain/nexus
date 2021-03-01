package ch.epfl.bluebrain.nexus.delta

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem => ActorSystemClassic}
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route, RouteResult}
import cats.effect.ExitCode
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.routes._
import ch.epfl.bluebrain.nexus.delta.sdk.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import ch.epfl.bluebrain.nexus.delta.service.plugin.{PluginsLoader, WiringInitializer}
import ch.epfl.bluebrain.nexus.delta.wiring.{DeltaModule, MigrationModule}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import com.typesafe.scalalogging.{Logger => Logging}
import izumi.distage.model.Locator
import kamon.Kamon
import monix.bio.{BIOApp, IO, Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.error.ConfigReaderFailures

import scala.concurrent.duration.DurationInt

object Main extends BIOApp {

  private val pluginEnvVariable = "DELTA_PLUGINS"
  private val log: Logging      = Logging[Main.type]

  override def run(args: List[String]): UIO[ExitCode] = {
    LoggerFactory.getLogger("Main") // initialize logging to suppress SLF4J error
    // TODO: disable this for now, but investigate why it happens
    System.setProperty("cats.effect.logNonDaemonThreadsOnExit", "false")
    val config = sys.env.get(pluginEnvVariable).fold(PluginLoaderConfig())(PluginLoaderConfig(_))
    start(_ => Task.unit, config).as(ExitCode.Success).attempt.map(_.fold(identity, identity))
  }

  private[delta] def start(preStart: Locator => Task[Unit], config: PluginLoaderConfig): IO[ExitCode, Unit] =
    for {
      (classLoader, pluginsDef) <- PluginsLoader(config).load.handleError
      _                         <- UIO.delay(log.info(s"Plugins discovered: ${pluginsDef.map(_.info).mkString(", ")}"))
      _                         <- validateDifferentPriority(pluginsDef)
      _                         <- validateDifferentName(pluginsDef)
      configNames                = pluginsDef.map(_.configFileName)
      (appConfig, mergedConfig) <- AppConfig.load(configNames, classLoader).handleError
      _                         <- initializeKamon(mergedConfig)
      modules                   <-
        if (MigrationState.isRunning)
          UIO.delay(log.info("Starting Delta in migration mode")) >>
            UIO.delay(MigrationModule(appConfig, mergedConfig, classLoader))
        else
          UIO.delay(log.info("Starting Delta in normal mode")) >>
            UIO.delay(DeltaModule(appConfig, mergedConfig, classLoader))

      (plugins, locator) <- WiringInitializer(modules, pluginsDef)(classLoader).handleError
      _                  <- preStart(locator).handleError
      _                  <- bootstrap(locator, plugins.flatMap(_.route)).handleError
    } yield ()

  private def validateDifferentPriority(pluginsDef: List[PluginDef]): IO[ExitCode, Unit] =
    if (pluginsDef.map(_.priority).distinct.size == pluginsDef.size) IO.unit
    else
      UIO.delay(
        log.warn(
          "Several plugins have the same priority:" +
            pluginsDef.map(p => s"name '${p.info.name}' priority '${p.priority}'").mkString(",")
        )
      ) >> IO.raiseError(ExitCode.Error)

  private def validateDifferentName(pluginsDef: List[PluginDef]): IO[ExitCode, Unit] =
    if (pluginsDef.map(_.info.name).distinct.size == pluginsDef.size) IO.unit
    else
      UIO.delay(
        log.warn(
          s"Several plugins have the same name: ${pluginsDef.map(p => s"name '${p.info.name}'").mkString(",")}"
        )
      ) >> IO.raiseError(ExitCode.Error)

  private def routes(locator: Locator, pluginRoutes: List[Route]): Route = {
    import akka.http.scaladsl.server.Directives._
    cors(locator.get[CorsSettings]) {
      handleExceptions(locator.get[ExceptionHandler]) {
        handleRejections(locator.get[RejectionHandler]) {
          concat(
            (pluginRoutes.toVector :+
              locator.get[VersionRoutes].routes :+
              locator.get[IdentitiesRoutes].routes :+
              locator.get[PermissionsRoutes].routes :+
              locator.get[RealmsRoutes].routes :+
              locator.get[AclsRoutes].routes :+
              locator.get[OrganizationsRoutes].routes :+
              locator.get[ProjectsRoutes].routes :+
              locator.get[SchemasRoutes].routes :+
              locator.get[ResolversRoutes].routes :+
              locator.get[ResourcesRoutes].routes): _*
          )
        }
      }
    }
  }

  private def bootstrap(locator: Locator, pluginRoutes: List[Route]): Task[Unit] =
    Task.delay {
      implicit val as: ActorSystemClassic = locator.get[ActorSystem[Nothing]].toClassic
      implicit val scheduler: Scheduler   = locator.get[Scheduler]
      implicit val cfg: AppConfig         = locator.get[AppConfig]
      val logger                          = locator.get[Logger]
      val cluster                         = Cluster(as)

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
              .bindFlow(RouteResult.routeToFlow(routes(locator, pluginRoutes)))
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
          ) >> terminateKamon >> terminateActorSystem()
        }

      cluster.registerOnMemberUp {
        logger.info(" === Cluster is LIVE === ")
        binding.runAsyncAndForget
      }

      cluster.joinSeedNodes(cfg.cluster.seedList)
    }

  private def kamonEnabled: Boolean =
    sys.env.getOrElse("KAMON_ENABLED", "true").toBooleanOption.getOrElse(true)

  private def initializeKamon(config: Config): UIO[Unit] =
    UIO.when(kamonEnabled)(UIO.delay(Kamon.init(config)))

  private def terminateKamon: Task[Unit] =
    if (kamonEnabled) Task.deferFuture(Kamon.stopModules()).timeout(15.seconds).onErrorRecover(_ => ()) >> Task.unit
    else Task.unit

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
