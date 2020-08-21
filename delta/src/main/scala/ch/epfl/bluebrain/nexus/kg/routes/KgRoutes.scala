package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinator
import ch.epfl.bluebrain.nexus.kg.cache.Caches._
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * Generates the routes for all the platform resources
  *
 * @param resources the resources operations
  */
@SuppressWarnings(Array("MaxParameters"))
class KgRoutes(
    resources: Resources[Task],
    resolvers: Resolvers[Task],
    views: Views[Task],
    storages: Storages[Task],
    schemas: Schemas[Task],
    files: Files[Task],
    archives: Archives[Task],
    tags: Tags[Task],
    acls: Acls[Task],
    realms: Realms[Task],
    coordinator: ProjectViewCoordinator[Task]
)(implicit
    system: ActorSystem,
    clients: Clients[Task],
    cache: Caches[Task],
    config: AppConfig
) extends AuthDirectives(acls, realms)(config.http, global) {
  import clients._
  implicit val um: FromEntityUnmarshaller[String] =
    PredefinedFromEntityUnmarshallers.stringUnmarshaller
      .forContentTypes(RdfMediaTypes.`application/sparql-query`, MediaTypes.`text/plain`)

  implicit private val projectCache: ProjectCache[Task]  = cache.project
  implicit private val orgCache: OrganizationCache[Task] = cache.org
  implicit private val viewCache: ViewCache[Task]        = cache.view
  implicit private val pagination: PaginationConfig      = config.pagination

  private def list(implicit caller: Caller, project: ProjectResource): Route = {
    val projectPath = project.value.path
    (get & paginated & searchParams(project) & pathEndOrSingleSlash) { (pagination, params) =>
      operationName(s"/${config.http.prefix}/resources/{org}/{project}") {
        (authorizeFor(projectPath, read) & extractUri) { implicit uri =>
          val listed =
            viewCache.getDefaultElasticSearch(ProjectRef(project.uuid)).flatMap(resources.list(_, params, pagination))
          complete(listed.runWithStatus(OK))
        }
      }
    }
  }

  private def projectEvents(implicit project: ProjectResource, caller: Caller): Route =
    (get & pathPrefix("events") & pathEndOrSingleSlash) {
      new EventRoutes(acls, realms, caller).projectRoutes(project)
    }

  private def createDefault(implicit caller: Caller, subject: Subject, project: ProjectResource): Route = {
    val projectPath =
      Path.Segment(project.value.label, Path.Slash(Path.Segment(project.value.organizationLabel, Path./)))
    (post & noParameter("rev".as[Long]) & pathEndOrSingleSlash) {
      operationName(s"/${config.http.prefix}/resources/{org}/{project}") {
        (authorizeFor(projectPath, ResourceRoutes.write) & projectNotDeprecated) {
          entity(as[Json]) { source =>
            complete(resources.create(unconstrainedRef, source).value.runWithStatus(Created))
          }
        }
      }
    }
  }

  private def routesSelector(
      segment: IdOrUnderscore
  )(implicit subject: Subject, caller: Caller, project: ProjectResource) =
    segment match {
      case Underscore                    => routeSelectorUndescore
      case SchemaId(`archiveSchemaUri`)  => new ArchiveRoutes(archives, acls, realms).routes
      case SchemaId(`resolverSchemaUri`) => new ResolverRoutes(resolvers, tags, acls, realms).routes
      case SchemaId(`viewSchemaUri`)     => new ViewRoutes(views, tags, acls, realms, coordinator).routes
      case SchemaId(`shaclSchemaUri`)    => new SchemaRoutes(schemas, tags, acls, realms).routes
      case SchemaId(`fileSchemaUri`)     => new FileRoutes(files, resources, tags, acls, realms).routes
      case SchemaId(`storageSchemaUri`)  => new StorageRoutes(storages, tags, acls, realms).routes
      case SchemaId(schema)              =>
        new ResourceRoutes(resources, tags, acls, realms, schema.ref).routes ~ list ~ createDefault
      case _                             => reject()
    }

  private def routeSelectorUndescore(implicit subject: Subject, caller: Caller, project: ProjectResource) =
    pathPrefix(IdSegment) { id =>
      // format: off
      onSuccess(resources.fetchSchema(Id(ProjectRef(project.uuid), id)).value.runToFuture) {
        case Right(`resolverRef`)         =>  new ResolverRoutes(resolvers, tags, acls, realms).routes(id)
        case Right(`viewRef`)             =>  new ViewRoutes(views, tags, acls, realms, coordinator).routes(id)
        case Right(`shaclRef`)            => new SchemaRoutes(schemas, tags, acls, realms).routes(id)
        case Right(`fileRef`)             => new FileRoutes(files, resources, tags, acls, realms).routes(id)
        case Right(`storageRef`)          => new StorageRoutes(storages, tags, acls, realms).routes(id)
        case Right(schema)                => new ResourceRoutes(resources, tags, acls, realms, schema).routes(id) ~ list ~ createDefault
        case Left(_: Rejection.NotFound)  => new ResourceRoutes(resources, tags, acls, realms, unconstrainedRef).routes(id) ~ list ~ createDefault
        case Left(err) => complete(err)
      }
      // format: on
    } ~ list ~ createDefault

  def routes: Route =
    concat(
      extractCaller { implicit caller =>
        implicit val subject: Subject = caller.subject
        concat(
          (get & pathPrefix(config.http.prefix / "resources" / Segment / "events") & pathEndOrSingleSlash) { label =>
            org(label).apply { implicit organization =>
              new EventRoutes(acls, realms, caller).organizationRoutes(organization)
            }
          },
          pathPrefix(config.http.prefix / "resources") {
            project.apply { implicit project =>
              pathPrefix(IdSegmentOrUnderscore)(routesSelector) ~ list ~ createDefault ~ projectEvents
            }
          },
          pathPrefix(config.http.prefix / "archives") {
            project.apply { implicit project =>
              routesSelector(SchemaId(archiveSchemaUri))
            }
          },
          pathPrefix(config.http.prefix / "views") {
            project.apply { implicit project =>
              routesSelector(SchemaId(viewSchemaUri))
            }
          },
          pathPrefix(config.http.prefix / "resolvers") {
            project.apply { implicit project =>
              routesSelector(SchemaId(resolverSchemaUri))
            }
          },
          pathPrefix(config.http.prefix / "schemas") {
            project.apply { implicit project =>
              routesSelector(SchemaId(shaclSchemaUri))
            }
          },
          pathPrefix(config.http.prefix / "storages") {
            project.apply { implicit project =>
              routesSelector(SchemaId(storageSchemaUri))
            }
          },
          pathPrefix(config.http.prefix / "files") {
            project.apply { implicit project =>
              routesSelector(SchemaId(fileSchemaUri))
            }
          }
        )
      }
    )
}
