package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.admin.client.types.Project
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
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import io.circe.Json
import kamon.Kamon
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class ResolverRoutes private[routes] (
    resolvers: Resolvers[Task],
    tags: Tags[Task],
    acls: Acls[Task],
    realms: Realms[Task]
)(implicit
    caller: Caller,
    project: Project,
    viewCache: ViewCache[Task],
    indexers: Clients[Task],
    config: ServiceConfig
) extends AuthDirectives(acls, realms) {

  import indexers._
  private val projectPath               = project.organizationLabel / project.label
  implicit private val subject: Subject = caller.subject

  /**
    * Routes for resolvers. Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/resolvers/{org}/{project}. E.g.: v1/views/myorg/myproject </li>
    *   <li> {prefix}/resources/{org}/{project}/{resolverSchemaUri}. E.g.: v1/resources/myorg/myproject/resolver </li>
    * </ul>
    */
  def routes: Route =
    concat(
      // Create resolver when id is not provided on the Uri (POST)
      (post & noParameter("rev".as[Long]) & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/resolvers/{org}/{project}") {
          Kamon.currentSpan().tag("resource.operation", "create")
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              complete(resolvers.create(source).value.runWithStatus(Created))
            }
          }
        }
      },
      // List resolvers
      (get & paginated & searchParams(fixedSchema = resolverSchemaUri) & pathEndOrSingleSlash) { (page, params) =>
        extractUri { implicit uri =>
          operationName(s"/${config.http.prefix}/resolvers/{org}/{project}") {
            authorizeFor(projectPath, read)(caller) {
              val listed = viewCache.getDefaultElasticSearch(project.ref).flatMap(resolvers.list(_, params, page))
              complete(listed.runWithStatus(OK))
            }
          }
        }
      },
      // Consume the '_' segment
      pathPrefix("_") {
        routesResourceResolution
      },
      // Consume the resolver id segment
      pathPrefix(IdSegment) { id =>
        routes(id)
      }
    )

  /**
    * Routes for resource resolution.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/resolvers/{org}/{project}/_. E.g.: v1/resolvers/myorg/myproject/_ </li>
    * </ul>
    */
  def routesResourceResolution: Route =
    // Consume the resource id segment
    (get & pathPrefix(IdSegment) & pathEndOrSingleSlash) { id =>
      operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/_/{resourceId}") {
        outputFormat(strict = false, Compacted) {
          case format: NonBinaryOutputFormat =>
            authorizeFor(projectPath, read)(caller) {
              concat(
                (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                  completeWithFormat(resolvers.resolve(id, rev).value.runWithStatus(OK))(format)
                },
                (parameter("tag") & noParameter("rev")) { tag =>
                  completeWithFormat(resolvers.resolve(id, tag).value.runWithStatus(OK))(format)
                },
                (noParameter("tag") & noParameter("rev")) {
                  completeWithFormat(resolvers.resolve(id).value.runWithStatus(OK))(format)
                }
              )
            }
          case other                         => failWith(InvalidOutputFormat(other.toString))
        }
      }
    }

  /**
    * Routes for resource resolution using the provided resolver.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/resolvers/{org}/{project}/{id}. E.g.: v1/resolvers/myorg/myproject/myresolver </li>
    *   <li> {prefix}/resources/{org}/{project}/{resolverSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/resolver/myresolver </li>
    *   <li> {prefix}/resources/{org}/{project}/_/{id}. E.g.: v1/resources/myorg/myproject/_/myresolver </li>
    * </ul>
    */
  def routesResourceResolution(id: AbsoluteIri): Route = {
    val resolverId = Id(project.ref, id)
    // Consume the resource id segment
    (get & pathPrefix(IdSegment) & pathEndOrSingleSlash) { resourceId =>
      operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}/{resourceId}") {
        outputFormat(strict = false, Compacted) {
          case format: NonBinaryOutputFormat =>
            authorizeFor(projectPath, read)(caller) {
              concat(
                (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                  completeWithFormat(resolvers.resolve(resolverId, resourceId, rev).value.runWithStatus(OK))(format)
                },
                (parameter("tag") & noParameter("rev")) { tag =>
                  completeWithFormat(resolvers.resolve(resolverId, resourceId, tag).value.runWithStatus(OK))(format)
                },
                (noParameter("tag") & noParameter("rev")) {
                  completeWithFormat(resolvers.resolve(resolverId, resourceId).value.runWithStatus(OK))(format)
                }
              )
            }
          case other                         => failWith(InvalidOutputFormat(other.toString))
        }
      }
    }
  }

  /**
    * Routes for resolvers when the id is specified.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/resolvers/{org}/{project}/{id}. E.g.: v1/resolvers/myorg/myproject/myresolver </li>
    *   <li> {prefix}/resources/{org}/{project}/_/{id}. E.g.: v1/resources/myorg/myproject/_/myresolver </li>
    *   <li> {prefix}/resources/{org}/{project}/{resolverSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/resolver/myresolver </li>
    * </ul>
    */
  def routes(id: AbsoluteIri): Route =
    concat(
      // Create or update a resolver (depending on rev query parameter)
      (put & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              parameter("rev".as[Long].?) {
                case None      =>
                  Kamon.currentSpan().tag("resource.operation", "create")
                  complete(resolvers.create(Id(project.ref, id), source).value.runWithStatus(Created))
                case Some(rev) =>
                  Kamon.currentSpan().tag("resource.operation", "update")
                  complete(resolvers.update(Id(project.ref, id), rev, source).value.runWithStatus(OK))
              }
            }
          }
        }
      },
      // Deprecate resolver
      (delete & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
        operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            complete(resolvers.deprecate(Id(project.ref, id), rev).value.runWithStatus(OK))
          }
        }
      },
      // Fetch resolver
      (get & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}") {
          outputFormat(strict = false, Compacted) {
            case format: NonBinaryOutputFormat =>
              authorizeFor(projectPath, read)(caller) {
                concat(
                  (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                    completeWithFormat(resolvers.fetch(Id(project.ref, id), rev).value.runWithStatus(OK))(format)
                  },
                  (parameter("tag") & noParameter("rev")) { tag =>
                    completeWithFormat(resolvers.fetch(Id(project.ref, id), tag).value.runWithStatus(OK))(format)
                  },
                  (noParameter("tag") & noParameter("rev")) {
                    completeWithFormat(resolvers.fetch(Id(project.ref, id)).value.runWithStatus(OK))(format)
                  }
                )
              }
            case other                         => failWith(InvalidOutputFormat(other.toString))
          }
        }
      },
      // Fetch resolver source
      (get & pathPrefix("source") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}/source") {
          authorizeFor(projectPath, read)(caller) {
            concat(
              (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                complete(resolvers.fetchSource(Id(project.ref, id), rev).value.runWithStatus(OK))
              },
              (parameter("tag") & noParameter("rev")) { tag =>
                complete(resolvers.fetchSource(Id(project.ref, id), tag).value.runWithStatus(OK))
              },
              (noParameter("tag") & noParameter("rev")) {
                complete(resolvers.fetchSource(Id(project.ref, id)).value.runWithStatus(OK))
              }
            )
          }
        }
      },
      // Incoming links
      (get & pathPrefix("incoming") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}/incoming") {
          fromPaginated.apply { implicit page =>
            extractUri { implicit uri =>
              authorizeFor(projectPath, read)(caller) {
                val listed = for {
                  view     <- viewCache.getDefaultSparql(project.ref).toNotFound(nxv.defaultSparqlIndex.value)
                  _        <- resolvers.fetchSource(Id(project.ref, id))
                  incoming <- EitherT.right[Rejection](resolvers.listIncoming(id, view, page))
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
          operationName(s"/${config.http.prefix}/resolvers/{org}/{project}/{id}/outgoing") {
            fromPaginated.apply { implicit page =>
              extractUri { implicit uri =>
                authorizeFor(projectPath, read)(caller) {
                  val listed = for {
                    view     <- viewCache.getDefaultSparql(project.ref).toNotFound(nxv.defaultSparqlIndex.value)
                    _        <- resolvers.fetchSource(Id(project.ref, id))
                    outgoing <- EitherT.right[Rejection](resolvers.listOutgoing(id, view, page, links))
                  } yield outgoing
                  complete(listed.value.runWithStatus(OK))
                }
              }
            }
          }
      },
      new TagRoutes("resolvers", tags, acls, realms, resolverRef, write).routes(id),
      routesResourceResolution(id)
    )
}
