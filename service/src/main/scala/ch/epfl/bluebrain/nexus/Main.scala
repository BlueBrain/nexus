package ch.epfl.bluebrain.nexus

import java.nio.file.Paths

import akka.actor.{ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult
import cats.effect.Effect
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.acls.Acls
import ch.epfl.bluebrain.nexus.clients.HttpClient
import ch.epfl.bluebrain.nexus.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.permissions.Permissions
import ch.epfl.bluebrain.nexus.realms.{Groups, Realms}
import ch.epfl.bluebrain.nexus.routes.Routes
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
    val cfg = sys.env.get("SERVICE_CONFIG_FILE") orElse sys.props.get("service.config.file") map { str =>
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
    implicit val httpCfg: HttpConfig = cfg.http
    implicit val eff: Effect[Task]   = Task.catsEffect(Scheduler.global)
    implicit val pc                  = cfg.permissions
    implicit val ac                  = cfg.acls
    implicit val rc                  = cfg.realms
    implicit val gc                  = cfg.groups
    implicit val pm                  = CanBlock.permit
    val client: HttpClient[Task]     = HttpClient[Task]

    val deferred = for {
      ps <- Deferred[Task, Permissions[Task]]
      as <- Deferred[Task, Acls[Task]]
      rs <- Deferred[Task, Realms[Task]]
      gt <- Groups[Task](client)
      pt <- Permissions[Task](as.get)
      at <- Acls[Task](ps.get)
      rt <- Realms[Task](as.get, gt, client)
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
    // TODO: Not required yet
//    System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

    cluster.registerOnMemberUp {
      logger.info("==== Cluster is Live ====")

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
