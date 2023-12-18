package ch.epfl.bluebrain.nexus.storage

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import cats.effect.{ExitCode, IO, IOApp}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.TransactionalFileCopier
import ch.epfl.bluebrain.nexus.storage.Storages.DiskStorage
import ch.epfl.bluebrain.nexus.storage.attributes.{AttributesCache, ContentTypeDetector}
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod
import ch.epfl.bluebrain.nexus.storage.config.AppConfig._
import ch.epfl.bluebrain.nexus.storage.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.storage.files.ValidateFile
import ch.epfl.bluebrain.nexus.storage.routes.Routes
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon

import java.nio.file.Paths
import java.time.Clock
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
// $COVERAGE-OFF$
object Main extends IOApp {

  def loadConfig(): Config = {
    val cfg = sys.env.get("STORAGE_CONFIG_FILE") orElse sys.props.get("storage.config.file") map { str =>
      val file = Paths.get(str).toAbsolutePath.toFile
      ConfigFactory.parseFile(file)
    } getOrElse ConfigFactory.empty()
    (cfg withFallback ConfigFactory.load()).resolve()
  }

  def setupMonitoring(config: Config): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Kamon.init(config)
    }
  }

  def shutdownMonitoring(): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Await.result(Kamon.stopModules(), 10.seconds)
    }
  }

  @SuppressWarnings(Array("UnusedMethodParameter"))
  override def run(args: List[String]): IO[ExitCode] = {
    val config = loadConfig()
    setupMonitoring(config)

    implicit val appConfig: AppConfig = Settings(config).appConfig

    implicit val as: ActorSystem                          = ActorSystem(appConfig.description.fullName, config)
    implicit val ec: ExecutionContext                     = as.dispatcher
    implicit val authorizationMethod: AuthorizationMethod = appConfig.authorization
    implicit val timeout                                  = Timeout(1.minute)
    implicit val clock                                    = Clock.systemUTC
    implicit val contentTypeDetector                      = new ContentTypeDetector(appConfig.mediaTypeDetector)

    val attributesCache: AttributesCache   = AttributesCache[AkkaSource]
    val validateFile: ValidateFile         = ValidateFile.mk(appConfig.storage)
    val copyFiles: TransactionalFileCopier = TransactionalFileCopier.mk()

    val storages: Storages[AkkaSource] =
      new DiskStorage(
        appConfig.storage,
        contentTypeDetector,
        appConfig.digest,
        attributesCache,
        validateFile,
        copyFiles
      )

    val logger: LoggingAdapter = Logging(as, getClass)

    logger.info("==== Cluster is Live ====")

    if (authorizationMethod == AuthorizationMethod.Anonymous) {
      logger.warning("The application has been configured with anonymous, the caller will not be verified !")
    }

    val routes: Route = Routes(storages)

    val httpBinding: Future[Http.ServerBinding] = {
      Http().newServerAt(appConfig.http.interface, appConfig.http.port).bind(routes)
    }
    httpBinding onComplete {
      case Success(binding) =>
        logger.info(s"Bound to ${binding.localAddress.getHostString}: ${binding.localAddress.getPort}")
      case Failure(th)      =>
        logger.error(th, "Failed to perform an http binding on {}:{}", appConfig.http.interface, appConfig.http.port)
        Await.result(as.terminate(), 10.seconds)
    }

    as.registerOnTermination {
      shutdownMonitoring()
    }
    // attempt to leave the cluster before shutting down
    val _ = sys.addShutdownHook {
      Await.result(as.terminate().map(_ => ()), 10.seconds)
    }

    IO.never.as(ExitCode.Success)
  }
}
// $COVERAGE-ON$
