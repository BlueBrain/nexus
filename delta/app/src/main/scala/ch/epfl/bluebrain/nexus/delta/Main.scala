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
import ch.epfl.bluebrain.nexus.delta.routes.{AclsRoutes, IdentitiesRoutes, OrganizationsRoutes, PermissionsRoutes, RealmsRoutes}
import ch.epfl.bluebrain.nexus.delta.wiring.DeltaModule
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import kamon.Kamon
import monix.bio.{BIOApp, Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.error.ConfigReaderFailures

import scala.concurrent.duration.DurationInt

// $COVERAGE-OFF$
object Main extends BIOApp {
  override def run(args: List[String]): UIO[ExitCode] = {
    LoggerFactory.getLogger("Main") // initialize logging to suppress SLF4J error
    AppConfig
      .load()
      .flatMap { case (cfg: AppConfig, config: Config) =>
        initializeKamon(config) >>
          Injector()
            .produceF[Task](DeltaModule(cfg, config), Roots.Everything)
            .use(bootstrap)
            .hideErrors >>
          UIO.pure(ExitCode.Success)
      }
      .onErrorHandleWith(configReaderErrorHandler)
  }

  private def routes(locator: Locator): Route = {
    import akka.http.scaladsl.server.Directives._
    cors(locator.get[CorsSettings]) {
      handleExceptions(locator.get[ExceptionHandler]) {
        handleRejections(locator.get[RejectionHandler]) {
          concat(
            locator.get[IdentitiesRoutes].routes,
            locator.get[PermissionsRoutes].routes,
            locator.get[RealmsRoutes].routes,
            locator.get[AclsRoutes].routes,
            locator.get[OrganizationsRoutes].routes
          )
        }
      }
    }
  }

  private def bootstrap(locator: Locator): Task[Unit] =
    Task.delay {
      implicit val as: ActorSystemClassic = locator.get[ActorSystem[Nothing]].toClassic
      implicit val scheduler: Scheduler   = locator.get[Scheduler]
      implicit val cfg: AppConfig         = locator.get[AppConfig]
      val logger                          = locator.get[Logger]
      val cluster                         = Cluster(as)

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
    if (kamonEnabled) UIO.delay(Kamon.init(config))
    else UIO.unit

  private def terminateKamon: Task[Unit] =
    if (kamonEnabled) Task.deferFuture(Kamon.stopModules()).timeout(15.seconds).onErrorRecover(_ => ()) >> Task.unit
    else Task.unit

  private def terminateActorSystem()(implicit as: ActorSystemClassic): Task[Unit] =
    Task.deferFuture(as.terminate()).timeout(15.seconds) >> Task.unit

  private def configReaderErrorHandler(failures: ConfigReaderFailures): UIO[ExitCode] = {
    val lines =
      "Error: The application configuration failed to load, due to:" ::
        failures.toList
          .flatMap { f =>
            f.origin match {
              case Some(o) => f.description :: s"  file: ${o.url.toString}" :: s"  line: ${o.lineNumber}" :: Nil
              case None    => f.description :: Nil
            }
          }
    UIO.delay(println(lines.mkString("\n"))) >> UIO.pure(ExitCode.Error)
  }
}
// $COVERAGE-ON$
