package ch.epfl.bluebrain.nexus.service

import java.nio.file.Paths

import akka.actor.{ActorSystem, Address, AddressFromURIString}
import akka.cluster.Cluster
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult
import cats.effect.Effect
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.Organizations
import ch.epfl.bluebrain.nexus.admin.projects.Projects
import ch.epfl.bluebrain.nexus.admin.routes.AdminRoutes
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.realms.{Groups, Realms}
import ch.epfl.bluebrain.nexus.iam.routes.IamRoutes
import ch.epfl.bluebrain.nexus.service.config.{ServiceConfig, Settings}
import ch.epfl.bluebrain.nexus.service.routes.{AppInfoRoutes, Routes}
import com.github.jsonldjava.core.DocumentLoader
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

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

  def bootstrapIam()(
      implicit system: ActorSystem,
      cfg: ServiceConfig
  ): (Permissions[Task], Acls[Task], Realms[Task]) = {
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    implicit val pc = cfg.iam.permissions
    implicit val ac = cfg.iam.acls
    implicit val rc = cfg.iam.realms
    implicit val gc = cfg.iam.groups
    implicit val hc = cfg.http
    implicit val pm = CanBlock.permit
    implicit val cl = HttpClient.untyped[Task]
    import system.dispatcher
    implicit val jc = HttpClient.withUnmarshaller[Task, Json]

    val deferred = for {
      //IAM dependencies
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

  def bootstrapAdmin()(
      implicit system: ActorSystem,
      cfg: ServiceConfig
  ): (Organizations[Task], Projects[Task], OrganizationCache[Task], ProjectCache[Task], IamClient[Task]) = {
    implicit val scheduler         = Scheduler.global
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    implicit val icc               = cfg.admin.iam
    implicit val kvc               = cfg.admin.keyValueStore
    implicit val pm                = CanBlock.permit

    val ic = IamClient[Task]
    val oc = OrganizationCache[Task]
    val pc = ProjectCache[Task]
    val deferred = for {
      orgs  <- Organizations(oc, ic)
      projs <- Projects(pc, orgs, ic)
    } yield (orgs, projs, oc, pc, ic)
    deferred.runSyncUnsafe()(Scheduler.global, pm)
  }

  def bootstrapIndexers(acls: Acls[Task], realms: Realms[Task], orgs: Organizations[Task], projects: Projects[Task])(
      implicit as: ActorSystem,
      cfg: ServiceConfig
  ): Unit = {
    implicit val ac                = cfg.iam.acls
    implicit val rc                = cfg.iam.realms
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    Acls.indexer[Task](acls).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
    Realms.indexer[Task](realms).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
    Organizations.indexer[Task](orgs).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
    Projects.indexer[Task](projects).runSyncUnsafe()(Scheduler.global, CanBlock.permit)
  }

  @SuppressWarnings(Array("UnusedMethodParameter"))
  def main(args: Array[String]): Unit = {
    val config = loadConfig()
    setupMonitoring(config)
    implicit val serviceConfig = Settings(config).serviceConfig
    implicit val as            = ActorSystem(serviceConfig.description.fullName, config)
    implicit val ec            = as.dispatcher
    val cluster                = Cluster(as)
    val seeds: List[Address] = serviceConfig.cluster.seeds.toList
      .flatMap(_.split(","))
      .map(addr => AddressFromURIString(s"akka://${serviceConfig.description.fullName}@$addr")) match {
      case Nil      => List(cluster.selfAddress)
      case nonEmpty => nonEmpty
    }

    val (perms, acls, realms)                               = bootstrapIam()
    val (orgs, projects, orgCache, projectCache, iamClient) = bootstrapAdmin()

    val logger = Logging(as, getClass)
    System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

    cluster.registerOnMemberUp {
      logger.info("==== Cluster is Live ====")
      bootstrapIndexers(acls, realms, orgs, projects)
      val iamRoutes   = IamRoutes(acls, realms, perms)
      val adminRoutes = AdminRoutes(orgs, projects, orgCache, projectCache, iamClient)
      val infoRoutes  = AppInfoRoutes(serviceConfig.description, cluster).routes

      val httpBinding = {
        Http().bindAndHandle(
          RouteResult.route2HandlerFlow(infoRoutes ~ Routes.wrap(iamRoutes ~ adminRoutes, serviceConfig.http)),
          serviceConfig.http.interface,
          serviceConfig.http.port
        )
      }
      httpBinding onComplete {
        case Success(binding) =>
          logger.info(s"Bound to ${binding.localAddress.getHostString}: ${binding.localAddress.getPort}")
        case Failure(th) =>
          logger.error(
            th,
            "Failed to perform an http binding on {}:{}",
            serviceConfig.http.interface,
            serviceConfig.http.port
          )
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
