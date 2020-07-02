package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
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
import ch.epfl.bluebrain.nexus.kg.routes.SchemaRoutes._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import io.circe.Json
import kamon.Kamon
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class SchemaRoutes private[routes] (schemas: Schemas[Task], tags: Tags[Task], acls: Acls[Task], realms: Realms[Task])(
    implicit
    caller: Caller,
    project: ProjectResource,
    viewCache: ViewCache[Task],
    indexers: Clients[Task],
    config: ServiceConfig
) extends AuthDirectives(acls, realms) {

  import indexers._
  private val projectPath               = project.value.path
  implicit private val subject: Subject = caller.subject

  /**
    * Routes for schemas. Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/schemas/{org}/{project}. E.g.: v1/schemas/myorg/myproject </li>
    *   <li> {prefix}/resources/{org}/{project}/{shaclSchemaUri}. E.g.: v1/resources/myorg/myproject/schema </li>
    * </ul>
    */
  def routes: Route =
    concat(
      // Create schema when id is not provided on the Uri (POST)
      (post & noParameter("rev".as[Long]) & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}") {
          Kamon.currentSpan().tag("resource.operation", "create")
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              complete(schemas.create(source).value.runWithStatus(Created))
            }
          }
        }
      },
      // List schemas
      (get & paginated & searchParams(fixedSchema = shaclSchemaUri) & pathEndOrSingleSlash) { (page, params) =>
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}") {
          extractUri { implicit uri =>
            authorizeFor(projectPath, read)(caller) {
              val listed =
                viewCache.getDefaultElasticSearch(ProjectRef(project.uuid)).flatMap(schemas.list(_, params, page))
              complete(listed.runWithStatus(OK))
            }
          }
        }
      },
      // Consume the schema id segment
      pathPrefix(IdSegment) { id =>
        routes(id)
      }
    )

  /**
    * Routes for schemas when the id is specified.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/schemas/{org}/{project}/{id}. E.g.: v1/schemas/myorg/myproject/myschema </li>
    *   <li> {prefix}/resources/{org}/{project}/{shaclSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/schema/myschema </li>
    * </ul>
    */
  def routes(id: AbsoluteIri): Route =
    concat(
      // Create or update a schema (depending on rev query parameter)
      (put & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}/{id}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              parameter("rev".as[Long].?) {
                case None      =>
                  Kamon.currentSpan().tag("resource.operation", "create")
                  complete(schemas.create(Id(ProjectRef(project.uuid), id), source).value.runWithStatus(Created))
                case Some(rev) =>
                  Kamon.currentSpan().tag("resource.operation", "update")
                  complete(schemas.update(Id(ProjectRef(project.uuid), id), rev, source).value.runWithStatus(OK))
              }
            }
          }
        }
      },
      // Deprecate schema
      (delete & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}/{id}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            complete(schemas.deprecate(Id(ProjectRef(project.uuid), id), rev).value.runWithStatus(OK))
          }
        }
      },
      // Fetch schema
      (get & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}/{id}") {
          outputFormat(strict = false, Compacted) {
            case format: NonBinaryOutputFormat =>
              authorizeFor(projectPath, read)(caller) {
                concat(
                  (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                    completeWithFormat(schemas.fetch(Id(ProjectRef(project.uuid), id), rev).value.runWithStatus(OK))(
                      format
                    )
                  },
                  (parameter("tag") & noParameter("rev")) { tag =>
                    completeWithFormat(schemas.fetch(Id(ProjectRef(project.uuid), id), tag).value.runWithStatus(OK))(
                      format
                    )
                  },
                  (noParameter("tag") & noParameter("rev")) {
                    completeWithFormat(schemas.fetch(Id(ProjectRef(project.uuid), id)).value.runWithStatus(OK))(format)
                  }
                )
              }
            case other                         => failWith(InvalidOutputFormat(other.toString))
          }
        }
      },
      // Fetch schema source
      (get & pathPrefix("source") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}/{id}/source") {
          authorizeFor(projectPath, read)(caller) {
            concat(
              (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                complete(schemas.fetchSource(Id(ProjectRef(project.uuid), id), rev).value.runWithStatus(OK))
              },
              (parameter("tag") & noParameter("rev")) { tag =>
                complete(schemas.fetchSource(Id(ProjectRef(project.uuid), id), tag).value.runWithStatus(OK))
              },
              (noParameter("tag") & noParameter("rev")) {
                complete(schemas.fetchSource(Id(ProjectRef(project.uuid), id)).value.runWithStatus(OK))
              }
            )
          }
        }
      },
      // Incoming links
      (get & pathPrefix("incoming") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/schemas/{org}/{project}/{id}/incoming") {
          fromPaginated.apply {
            implicit page =>
              extractUri { implicit uri =>
                authorizeFor(projectPath, read)(caller) {
                  val listed = for {
                    view     <-
                      viewCache.getDefaultSparql(ProjectRef(project.uuid)).toNotFound(nxv.defaultSparqlIndex.value)
                    _        <- schemas.fetchSource(Id(ProjectRef(project.uuid), id))
                    incoming <- EitherT.right[Rejection](schemas.listIncoming(id, view, page))
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
          operationName(s"/${config.http.prefix}/schemas/{org}/{project}/{id}/outgoing") {
            fromPaginated.apply {
              implicit page =>
                extractUri { implicit uri =>
                  authorizeFor(projectPath, read)(caller) {
                    val listed = for {
                      view     <-
                        viewCache.getDefaultSparql(ProjectRef(project.uuid)).toNotFound(nxv.defaultSparqlIndex.value)
                      _        <- schemas.fetchSource(Id(ProjectRef(project.uuid), id))
                      outgoing <- EitherT.right[Rejection](schemas.listOutgoing(id, view, page, links))
                    } yield outgoing
                    complete(listed.value.runWithStatus(OK))
                  }
                }
            }
          }
      },
      new TagRoutes("schemas", tags, acls, realms, shaclRef, write).routes(id)
    )
}

object SchemaRoutes {
  val write: Permission = Permission.unsafe("schemas/write")
}
