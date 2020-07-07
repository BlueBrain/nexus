package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError.InvalidOutputFormat
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.service.config.AppConfig.PaginationConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.config.{AppConfig, Permissions}
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import io.circe.Json
import kamon.Kamon
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class StorageRoutes private[routes] (
    storages: Storages[Task],
    tags: Tags[Task],
    acls: Acls[Task],
    realms: Realms[Task]
)(implicit
    system: ActorSystem,
    caller: Caller,
    project: ProjectResource,
    viewCache: ViewCache[Task],
    indexers: Clients[Task],
    config: AppConfig
) extends AuthDirectives(acls, realms)(config.http, global) {
  import indexers._
  private val projectPath                           = project.value.path
  implicit private val subject: Subject             = caller.subject
  implicit private val pagination: PaginationConfig = config.pagination

  /**
    * Routes for storages. Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/storages/{org}/{project}. E.g.: v1/storages/myorg/myproject </li>
    *   <li> {prefix}/resources/{org}/{project}/{storageSchemaUri}. E.g.: v1/resources/myorg/myproject/storage </li>
    * </ul>
    */
  def routes: Route =
    concat(
      // Create storage when id is not provided on the Uri (POST)
      (post & noParameter("rev".as[Long]) & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/storages/{org}/{project}") {
          Kamon.currentSpan().tag("resource.operation", "create")
          (authorizeFor(projectPath, Permissions.storages.write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              complete(storages.create(source).value.runWithStatus(Created))
            }
          }
        }
      },
      // List storages
      (get & paginated & searchParams(fixedSchema = storageSchemaUri) & pathEndOrSingleSlash) { (page, params) =>
        operationName(s"/${config.http.prefix}/storages/{org}/{project}") {
          extractUri { implicit uri =>
            authorizeFor(projectPath, Permissions.storages.read)(caller) {
              val listed =
                viewCache.getDefaultElasticSearch(ProjectRef(project.uuid)).flatMap(storages.list(_, params, page))
              complete(listed.runWithStatus(OK))
            }
          }
        }
      },
      // Consume the storage id segment
      pathPrefix(IdSegment) { id =>
        routes(id)
      }
    )

  /**
    * Routes for storages when the id is specified.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/storages/{org}/{project}/{id}. E.g.: v1/storages/myorg/myproject/mystorage </li>
    *   <li> {prefix}/resources/{org}/{project}/{storageSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/storage/mystorage </li>
    * </ul>
    */
  def routes(id: AbsoluteIri): Route =
    concat(
      // Create or update a storage (depending on rev query parameter)
      (put & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/storages/{org}/{project}/{id}") {
          (authorizeFor(projectPath, Permissions.storages.write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              parameter("rev".as[Long].?) {
                case None      =>
                  Kamon.currentSpan().tag("resource.operation", "create")
                  complete(storages.create(Id(ProjectRef(project.uuid), id), source).value.runWithStatus(Created))
                case Some(rev) =>
                  Kamon.currentSpan().tag("resource.operation", "update")
                  complete(storages.update(Id(ProjectRef(project.uuid), id), rev, source).value.runWithStatus(OK))
              }
            }
          }
        }
      },
      // Deprecate storage
      (delete & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
        operationName(s"/${config.http.prefix}/storages/{org}/{project}/{id}") {
          (authorizeFor(projectPath, Permissions.storages.write) & projectNotDeprecated) {
            complete(storages.deprecate(Id(ProjectRef(project.uuid), id), rev).value.runWithStatus(OK))
          }
        }
      },
      // Fetch storage
      (get & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/storages/{org}/{project}/{id}") {
          outputFormat(strict = false, Compacted) {
            case format: NonBinaryOutputFormat =>
              authorizeFor(projectPath, Permissions.storages.read)(caller) {
                concat(
                  (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                    completeWithFormat(storages.fetch(Id(ProjectRef(project.uuid), id), rev).value.runWithStatus(OK))(
                      format
                    )
                  },
                  (parameter("tag") & noParameter("rev")) { tag =>
                    completeWithFormat(storages.fetch(Id(ProjectRef(project.uuid), id), tag).value.runWithStatus(OK))(
                      format
                    )
                  },
                  (noParameter("tag") & noParameter("rev")) {
                    completeWithFormat(storages.fetch(Id(ProjectRef(project.uuid), id)).value.runWithStatus(OK))(format)
                  }
                )
              }
            case other                         => failWith(InvalidOutputFormat(other.toString))
          }
        }
      },
      // Fetch storage source
      (get & pathPrefix("source") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/storages/{org}/{project}/{id}/source") {
          authorizeFor(projectPath, Permissions.storages.read)(caller) {
            concat(
              (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                complete(storages.fetchSource(Id(ProjectRef(project.uuid), id), rev).value.runWithStatus(OK))
              },
              (parameter("tag") & noParameter("rev")) { tag =>
                complete(storages.fetchSource(Id(ProjectRef(project.uuid), id), tag).value.runWithStatus(OK))
              },
              (noParameter("tag") & noParameter("rev")) {
                complete(storages.fetchSource(Id(ProjectRef(project.uuid), id)).value.runWithStatus(OK))
              }
            )
          }
        }
      },
      // Incoming links
      (get & pathPrefix("incoming") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/storages/{org}/{project}/{id}/incoming") {
          fromPaginated.apply {
            implicit page =>
              extractUri {
                implicit uri =>
                  authorizeFor(projectPath, Permissions.storages.read)(caller) {
                    val listed = for {
                      view     <-
                        viewCache.getDefaultSparql(ProjectRef(project.uuid)).toNotFound(nxv.defaultSparqlIndex.value)
                      _        <- storages.fetchSource(Id(ProjectRef(project.uuid), id))
                      incoming <- EitherT.right[Rejection](storages.listIncoming(id, view, page))
                    } yield incoming
                    complete(listed.value.runWithStatus(OK))
                  }
              }
          }
        }
      },
      // Outgoing links
      (get & pathPrefix("outgoing") & parameter("includeExternalLinks".as[Boolean] ? true) & pathEndOrSingleSlash) {
        links =>
          operationName(s"/${config.http.prefix}/storages/{org}/{project}/{id}/outgoing") {
            fromPaginated.apply {
              implicit page =>
                extractUri {
                  implicit uri =>
                    authorizeFor(projectPath, Permissions.storages.read)(caller) {
                      val listed = for {
                        view     <-
                          viewCache.getDefaultSparql(ProjectRef(project.uuid)).toNotFound(nxv.defaultSparqlIndex.value)
                        _        <- storages.fetchSource(Id(ProjectRef(project.uuid), id))
                        outgoing <- EitherT.right[Rejection](storages.listOutgoing(id, view, page, links))
                      } yield outgoing
                      complete(listed.value.runWithStatus(OK))
                    }
                }
            }
          }
      },
      new TagRoutes("storages", tags, acls, realms, storageRef, Permissions.storages.write).routes(id)
    )
}
