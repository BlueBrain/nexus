package ch.epfl.bluebrain.nexus.delta

import java.nio.file.Paths
import java.time.Clock

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
import ch.epfl.bluebrain.nexus.commons.es.client.{ElasticSearchClient, ElasticSearchDecoder}
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.{untyped, withUnmarshaller}
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.{HttpConfig, _}
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.delta.routes.{AppInfoRoutes, Routes}
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.permissions.Permissions
import ch.epfl.bluebrain.nexus.iam.realms.{Groups, Realms}
import ch.epfl.bluebrain.nexus.iam.routes.IamRoutes
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache
import ch.epfl.bluebrain.nexus.kg.async.{ProjectAttributesCoordinator, ProjectViewCoordinator}
import ch.epfl.bluebrain.nexus.kg.cache.{Caches, ResolverCache, StorageCache, ViewCache}
import ch.epfl.bluebrain.nexus.kg.resolve.{Materializer, ProjectResolution}
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.routes.{Clients, KgRoutes}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.FetchAttributes
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import ch.epfl.bluebrain.nexus.storage.client.config.StorageClientConfig
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

//noinspection TypeAnnotation
// $COVERAGE-OFF$
object Main {
  def loadConfig(): Config = {
    val cfg = sys.env.get("CONFIG_FILE") orElse sys.props.get("service.config.file") map { str =>
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

  def shutdownMonitoring()(implicit cfg: AppConfig): Unit = {
    if (sys.env.getOrElse("KAMON_ENABLED", "false").toBoolean) {
      Await.result(Kamon.stopModules(), cfg.runtime.shutdownTimeout)
    }
  }

  def bootstrapIam()(implicit
      system: ActorSystem,
      cfg: AppConfig
  ): (Permissions[Task], Acls[Task], Realms[Task]) = {
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    implicit val pc                = cfg.permissions
    implicit val ac                = cfg.acls
    implicit val rc                = cfg.realms
    implicit val gc                = cfg.groups
    implicit val hc                = cfg.http
    implicit val pm                = CanBlock.permit
    implicit val cl                = HttpClient.untyped[Task]
    import system.dispatcher
    implicit val jc                = HttpClient.withUnmarshaller[Task, Json]

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

  def bootstrapAdmin(acls: Acls[Task])(implicit
      system: ActorSystem,
      cfg: AppConfig
  ): (Organizations[Task], Projects[Task], OrganizationCache[Task], ProjectCache[Task]) = {
    implicit val http: HttpConfig  = cfg.http
    implicit val scheduler         = Scheduler.global
    implicit val eff: Effect[Task] = Task.catsEffect(Scheduler.global)
    implicit val kvs               = cfg.keyValueStore
    implicit val kvc               = kvs.keyValueStoreConfig
    implicit val pm                = CanBlock.permit

    val oc       = OrganizationCache[Task]
    val pc       = ProjectCache[Task]
    val deferred = for {
      orgs  <- Organizations(oc, acls, cfg.saCaller)
      projs <- Projects(pc, orgs, acls, cfg.saCaller)
    } yield (orgs, projs, oc, pc)
    deferred.runSyncUnsafe()(Scheduler.global, pm)
  }

  def bootstrapKg(
      acls: Acls[Task],
      orgCache: OrganizationCache[Task],
      projectCache: ProjectCache[Task]
  )(implicit
      system: ActorSystem,
      cfg: AppConfig
  ): (
      Resources[Task],
      Storages[Task],
      Files[Task],
      Archives[Task],
      Views[Task],
      Resolvers[Task],
      Schemas[Task],
      Tags[Task],
      Clients[Task],
      Caches[Task]
  ) = {
    implicit val eff: Effect[Task]    = Task.catsEffect(Scheduler.global)
    implicit val pm: CanBlock         = CanBlock.permit
    implicit val clock                = Clock.systemUTC
    implicit val kvs                  = cfg.keyValueStore
    implicit val kvc                  = kvs.keyValueStoreConfig
    implicit val agg                  = cfg.aggregate
    implicit val archivesCfg          = cfg.archives
    implicit val scheduler: Scheduler = Scheduler.global

    val repo: Repo[Task]                          = Repo[Task].runSyncUnsafe()(Scheduler.global, pm)
    implicit val cache: Caches[Task]              =
      Caches(
        orgCache,
        projectCache,
        ViewCache[Task],
        ResolverCache[Task],
        StorageCache[Task],
        ArchiveCache[Task].runSyncUnsafe()(Scheduler.global, pm)
      )
    implicit val pc: ProjectCache[Task]           = cache.project
    implicit val projectResolution                =
      ProjectResolution.task(repo, cache.resolver, cache.project, acls, cfg.saCaller)
    implicit val materializer: Materializer[Task] = new Materializer[Task](projectResolution, cache.project)

    implicit val utClient            = untyped[Task]
    implicit val jsonClient          = withUnmarshaller[Task, Json]
    implicit val sparqlResultsClient = withUnmarshaller[Task, SparqlResults]
    implicit val esDecoders          = ElasticSearchDecoder[Json]
    implicit val qrClient            = withUnmarshaller[Task, QueryResults[Json]]

    def defaultSparqlClient(implicit config: SparqlConfig): BlazegraphClient[Task] = {
      implicit val retryConfig = config.query
      BlazegraphClient[Task](config.base, config.defaultIndex, config.akkaCredentials)
    }

    def defaultElasticSearchClient(implicit config: ElasticSearchConfig): ElasticSearchClient[Task] = {
      implicit val retryConfig = config.query
      ElasticSearchClient[Task](config.base)
    }

    implicit val clients: Clients[Task] = {
      implicit val spCfg         = cfg.sparql
      implicit val esCfg         = cfg.elasticSearch
      val sparql                 = defaultSparqlClient
      implicit val elasticSearch = defaultElasticSearchClient
      implicit val sparqlClient  = sparql
      implicit val storageConfig = StorageClientConfig(url"${cfg.storage.remoteDisk.defaultEndpoint}")
      implicit val storageClient = StorageClient[Task]
      Clients()
    }

    val resources: Resources[Task] = Resources[Task](repo)
    val storages: Storages[Task]   = Storages[Task](repo, cache.storage)
    val files: Files[Task]         = Files[Task](repo, cache.storage)
    val archives: Archives[Task]   = Archives[Task](resources, files, cache)
    val views: Views[Task]         = Views[Task](repo, cache.view)
    val resolvers: Resolvers[Task] = Resolvers[Task](repo, cache.resolver)
    val schemas: Schemas[Task]     = Schemas[Task](repo)
    val tags: Tags[Task]           = Tags[Task](repo)

    (resources, storages, files, archives, views, resolvers, schemas, tags, clients, cache)
  }

  def bootstrapIndexers(
      acls: Acls[Task],
      realms: Realms[Task],
      orgs: Organizations[Task],
      projects: Projects[Task],
      resources: Resources[Task],
      files: Files[Task],
      storages: Storages[Task],
      views: Views[Task],
      resolvers: Resolvers[Task],
      cache: Caches[Task]
  )(implicit
      as: ActorSystem,
      cfg: AppConfig,
      clients: Clients[Task]
  ): ProjectViewCoordinator[Task] = {
    implicit val ac                     = cfg.acls
    implicit val rc                     = cfg.realms
    implicit val eff: Effect[Task]      = Task.catsEffect(Scheduler.global)
    implicit val c: Caches[Task]        = cache
    implicit val pc: ProjectCache[Task] = cache.project

    val pvcF = for {
      _           <- Acls.indexer[Task](acls)
      _           <- Realms.indexer[Task](realms)
      _           <- Organizations.indexer[Task](orgs)
      _           <- Projects.indexer[Task](projects)
      _           <- Views.indexer[Task](views, projects)
      _           <- Resolvers.indexer[Task](resolvers, projects)
      _           <- Storages.indexer[Task](storages, projects)
      projections <- Projections[Task, String]
      fa           = FetchAttributes.apply[Task]
      pvc         <- ProjectViewCoordinator(resources, cache, acls, cfg.saCaller)(cfg, as, clients, projections)
      pac         <- ProjectAttributesCoordinator(files, cache)(cfg, fa, as, projections)
      _           <- ProjectInitializer.fromCache[Task](storages, views, resolvers, pvc, pac)
    } yield pvc
    pvcF.runSyncUnsafe(30.seconds)(Scheduler.global, CanBlock.permit)
  }

  @SuppressWarnings(Array("UnusedMethodParameter"))
  def main(args: Array[String]): Unit = {
    val config               = loadConfig()
    setupMonitoring(config)
    implicit val cfg         = Settings(config).appConfig
    implicit val as          = ActorSystem(cfg.description.fullName, config)
    implicit val pm          = CanBlock.permit
    implicit val ec          = as.dispatcher
    implicit val hc          = cfg.http
    val cluster              = Cluster(as)
    val seeds: List[Address] = cfg.cluster.seeds.toList
      .flatMap(_.split(","))
      .map(addr => AddressFromURIString(s"akka://${cfg.description.fullName}@$addr")) match {
      case Nil      => List(cluster.selfAddress)
      case nonEmpty => nonEmpty
    }

    val (perms, acls, realms)                                                                   = bootstrapIam()
    val (orgs, projects, orgCache, projectCache)                                                = bootstrapAdmin(acls)
    val (resources, storages, files, archives, views, resolvers, schemas, tags, clients, cache) =
      bootstrapKg(acls, orgCache, projectCache)
    implicit val cl                                                                             = clients
    implicit val cc                                                                             = cache
    val logger                                                                                  = Logging(as, getClass)
    System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

    cluster.registerOnMemberUp {
      logger.info("==== Cluster is Live ====")

      if (sys.env.getOrElse("MIGRATE_V13_TO_V14", "false").toBoolean) {
        MigrateV13ToV14.migrate(cfg, as, Scheduler.global, pm)
      }

      if (sys.env.getOrElse("REPAIR_FROM_MESSAGES", "false").toBoolean) {
        RepairFromMessages.repair(cfg, as, Scheduler.global, pm)
      }

      val projectViewCoordinator =
        bootstrapIndexers(acls, realms, orgs, projects, resources, files, storages, views, resolvers, cache)
      val iamRoutes              = IamRoutes(acls, realms, perms)
      val adminRoutes            = AdminRoutes(orgs, projects, orgCache, projectCache, acls, realms)
      val infoRoutes             = AppInfoRoutes(cfg.description, cluster, clients).routes
      val kgRoutes               = new KgRoutes(
        resources,
        resolvers,
        views,
        storages,
        schemas,
        files,
        archives,
        tags,
        acls,
        realms,
        projectViewCoordinator
      ).routes

      val httpBinding = {
        Http().bindAndHandle(
          RouteResult.route2HandlerFlow(infoRoutes ~ Routes.wrap(iamRoutes ~ adminRoutes ~ kgRoutes)),
          cfg.http.interface,
          cfg.http.port
        )
      }
      httpBinding onComplete {
        case Success(binding) =>
          logger.info(s"Bound to ${binding.localAddress.getHostString}: ${binding.localAddress.getPort}")
        case Failure(th)      =>
          logger.error(
            th,
            "Failed to perform an http binding on {}:{}",
            cfg.http.interface,
            cfg.http.port
          )
          Await.result(as.terminate(), cfg.runtime.shutdownTimeout)
      }
    }

    cluster.joinSeedNodes(seeds)

    as.registerOnTermination {
      cluster.leave(cluster.selfAddress)
      shutdownMonitoring()
    }
    // attempt to leave the cluster before shutting down
    val _ = sys.addShutdownHook {
      Await.result(as.terminate().map(_ => ())(as.dispatcher), cfg.runtime.shutdownTimeout)
    }
  }
}
// $COVERAGE-ON$
