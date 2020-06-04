package ch.epfl.bluebrain.nexus.admin

import java.nio.file.Paths

import akka.actor.{ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.admin.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.admin.index._
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import ch.epfl.bluebrain.nexus.admin.routes._
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import com.github.jsonldjava.core.DocumentLoader
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
// $COVERAGE-OFF$
object Main {

  def loadConfig(): Config = {
    val cfg = sys.env
      .get("ADMIN_CONFIG_FILE")
      .orElse(sys.props.get("admin.config.file"))
      .map { str =>
        val file = Paths.get(str).toAbsolutePath.toFile
        ConfigFactory.parseFile(file)
      }
      .getOrElse(ConfigFactory.empty)

    cfg.withFallback(ConfigFactory.load()).resolve()
  }

  def setupMonitoring(config: Config): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Kamon.reconfigure(config)
      Kamon.loadModules()
    }
  }
  def shutdownMonitoring(): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Await.result(Kamon.stopModules(), 10.seconds)
    }
  }

  @SuppressWarnings(Array("UnusedMethodParameter"))
  def main(args: Array[String]): Unit = {
    System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")
    val config = loadConfig()
    setupMonitoring(config)

    implicit val appConfig            = new Settings(config).appConfig
    implicit val storeConfig          = appConfig.keyValueStore
    implicit val as: ActorSystem      = ActorSystem(appConfig.description.fullName, config)
    implicit val scheduler: Scheduler = Scheduler.global
    implicit val iamConfig            = appConfig.iam
    implicit val iamClient            = IamClient[Task]

    val logger = Logging(as, getClass)

    val cluster = Cluster(as)

    val seeds: List[Address] = appConfig.cluster.seeds.toList
      .flatMap(_.split(","))
      .map(addr => AddressFromURIString(s"akka://${appConfig.description.fullName}@$addr")) match {
      case Nil      => List(cluster.selfAddress)
      case nonEmpty => nonEmpty
    }
    implicit val orgIndex: OrganizationCache[Task] = OrganizationCache[Task]
    implicit val projectIndex: ProjectCache[Task]  = ProjectCache[Task]
    val organizations: Organizations[Task]         = Organizations[Task](orgIndex, iamClient).runSyncUnsafe()
    val projects: Projects[Task]                   = Projects(projectIndex, organizations, iamClient).runSyncUnsafe()

    cluster.registerOnMemberUp {
      logger.info("==== Cluster is Live ====")

      if (sys.env.getOrElse("REPAIR_FROM_MESSAGES", "false").toBoolean) {
        Task.fromFuture(RepairFromMessages.repair(organizations, projects)).runSyncUnsafe()
      }

      bootstrapIndexers(organizations, projects)
      val routes: Route = Routes(organizations, projects)
      val httpBinding   = Http().bindAndHandle(routes, appConfig.http.interface, appConfig.http.port)

      httpBinding.onComplete {
        case Success(binding) =>
          logger.info(s"Bound to ${binding.localAddress.getHostString}: ${binding.localAddress.getPort}")
        case Failure(th) =>
          logger.error(th, "Failed to perform an http binding on {}:{}", appConfig.http.interface, appConfig.http.port)
          Await.result(as.terminate(), 10.seconds)
      }
    }

    cluster.joinSeedNodes(seeds)

    as.registerOnTermination {
      cluster.leave(cluster.selfAddress)
      shutdownMonitoring()
    }
    // attempt to leave the cluster before shutting down
    val _ = sys.addShutdownHook {
      Await.result(as.terminate().map(_ => ())(as.dispatcher), 10.seconds)
    }
  }

  def bootstrapIndexers(
      orgs: Organizations[Task],
      projects: Projects[Task]
  )(implicit as: ActorSystem, cfg: AppConfig): Unit = {
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    Organizations.indexer[Task](orgs).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
    Projects.indexer[Task](projects).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
  }
}
// $COVERAGE-ON$
