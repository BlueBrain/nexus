package ch.epfl.bluebrain.nexus.iam

import java.nio.file.Paths

import _root_.io.circe.Json
import akka.actor.{ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult
import cats.effect.Effect
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.iam.acls._
import ch.epfl.bluebrain.nexus.iam.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.realms.{Groups, Realms}
import ch.epfl.bluebrain.nexus.iam.routes._
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
    val cfg = sys.env.get("IAM_CONFIG_FILE") orElse sys.props.get("iam.config.file") map { str =>
      val file = Paths.get(str).toAbsolutePath.toFile
      ConfigFactory.parseFile(file)
    } getOrElse ConfigFactory.empty()
    (cfg withFallback ConfigFactory.load()).resolve()
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

  def bootstrap()(implicit system: ActorSystem, cfg: AppConfig): (Permissions[Task], Acls[Task], Realms[Task]) = {
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    implicit val pc = cfg.permissions
    implicit val ac = cfg.acls
    implicit val rc = cfg.realms
    implicit val gc = cfg.groups
    implicit val pm = CanBlock.permit
    implicit val cl = HttpClient.untyped[Task]
    import system.dispatcher
    implicit val jc = HttpClient.withUnmarshaller[Task, Json]

    val deferred = for {
      ps <- Deferred[Task, Permissions[Task]]
      as <- Deferred[Task, Acls[Task]]
      rs <- Deferred[Task, Realms[Task]]
      gt <- Groups[Task]()
      pt <- Permissions[Task](as.get)
      at <- Acls[Task](ps.get)
      rt <- Realms[Task](as.get, gt)
      _  <- ps.complete(pt)
      _  <- as.complete(at)
      _  <- rs.complete(rt)
    } yield (pt, at, rt)
    deferred.runSyncUnsafe()(Scheduler.global, pm)
  }

  def bootstrapIndexers(acls: Acls[Task], realms: Realms[Task])(implicit as: ActorSystem, cfg: AppConfig): Unit = {
    implicit val ac                = cfg.acls
    implicit val rc                = cfg.realms
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    Acls.indexer[Task](acls).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
    Realms.indexer[Task](realms).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
  }

  @SuppressWarnings(Array("UnusedMethodParameter"))
  def main(args: Array[String]): Unit = {
    val config = loadConfig()
    setupMonitoring(config)

    implicit val appConfig = Settings(config).appConfig
    implicit val as        = ActorSystem(appConfig.description.fullName, config)
    implicit val ec        = as.dispatcher

    val cluster = Cluster(as)
    val seeds: List[Address] = appConfig.cluster.seeds.toList
      .flatMap(_.split(","))
      .map(addr => AddressFromURIString(s"akka://${appConfig.description.fullName}@$addr")) match {
      case Nil      => List(cluster.selfAddress)
      case nonEmpty => nonEmpty
    }

    val (perms, acls, realms) = bootstrap()

    val logger = Logging(as, getClass)
    System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

    cluster.registerOnMemberUp {
      logger.info("==== Cluster is Live ====")

      if (sys.env.getOrElse("REPAIR_FROM_MESSAGES", "false").toBoolean) {
        RepairFromMessages.repair(perms, realms, acls)(as, Scheduler.global, CanBlock.permit)
      }

      bootstrapIndexers(acls, realms)
      val routes = Routes(acls, realms, perms)

      val httpBinding = {
        Http().bindAndHandle(RouteResult.route2HandlerFlow(routes), appConfig.http.interface, appConfig.http.port)
      }
      httpBinding onComplete {
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
}

// $COVERAGE-ON$
