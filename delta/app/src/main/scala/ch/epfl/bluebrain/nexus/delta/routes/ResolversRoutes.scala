package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resolvers.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.searchParams
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.MultiResolutionResult.multiResolutionJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{MultiResolution, MultiResolutionResult, Resolver, ResolverRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{searchResultsEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The resolver routes
  * @param identities the identity module
  * @param acls  the acls module
  * @param projects the projects module
  * @param resolvers the resolvers module
  */
final class ResolversRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    resolvers: Resolvers,
    multiResolution: MultiResolution
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment
  implicit private val resolverContext: ContextValue = Resolvers.context
  implicit private val fetchProject: FetchProject    = projects.fetch

  private def resolverSearchParams(implicit projectRef: ProjectRef, caller: Caller): Directive1[ResolverSearchParams] =
    (searchParams & types).tflatMap { case (deprecated, rev, createdBy, updatedBy, types) =>
      callerAcls.map { aclsCol =>
        ResolverSearchParams(
          Some(projectRef),
          deprecated,
          rev,
          createdBy,
          updatedBy,
          types,
          resolver => aclsCol.exists(caller.identities, Read, AclAddress.Project(resolver.project))
        )
      }
    }

  // TODO: SSE missing for resolver events for all organization and for a particular project
  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("resolvers") {
          concat(
            // SSE resolvers for all events
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/resolvers/events") {
                  authorizeFor(AclAddress.Root, events.read).apply {
                    lastEventId { offset =>
                      emit(resolvers.events(offset))
                    }
                  }
                }
              }
            },
            projectRef(projects).apply { implicit ref =>
              val projectAddress = AclAddress.Project(ref)
              val authorizeRead  = authorizeFor(projectAddress, Read)
              val authorizeWrite = authorizeFor(projectAddress, Write)
              concat(
                (pathEndOrSingleSlash & operationName(s"$prefixSegment/resolvers/{org}/{project}")) {
                  concat(
                    // List resolvers
                    (get & extractUri & paginated & resolverSearchParams & sort[Resolver]) {
                      (uri, pagination, params, order) =>
                        authorizeRead {
                          implicit val sEnc: SearchEncoder[ResolverResource] = searchResultsEncoder(pagination, uri)
                          emit(resolvers.list(pagination, params, order))
                        }
                    },
                    // Create a resolver without an id segment
                    (post & noParameter("rev") & entity(as[Json])) { payload =>
                      authorizeWrite {
                        emit(Created, resolvers.create(ref, payload).map(_.void))
                      }
                    }
                  )
                },
                idSegment { id =>
                  concat(
                    pathEndOrSingleSlash {
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}") {
                        concat(
                          put {
                            authorizeWrite {
                              (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                case (None, payload)      =>
                                  // Create a resolver with an id segment
                                  emit(
                                    Created,
                                    resolvers.create(id, ref, payload).map(_.void)
                                  )
                                case (Some(rev), payload) =>
                                  // Update a resolver
                                  emit(
                                    resolvers.update(id, ref, rev, payload).map(_.void)
                                  )
                              }
                            }
                          },
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeWrite {
                              // Deprecate a resolver
                              emit(resolvers.deprecate(id, ref, rev).map(_.void))
                            }
                          },
                          // Fetches a resolver
                          get {
                            fetch(id, ref)
                          }
                        )
                      }
                    },
                    // Fetches a resolver original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}/source") {
                        fetchMap(id, ref, res => JsonSource(res.value.source, res.value.id))
                      }
                    },
                    // Tags
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a resolver tags
                          get {
                            fetchMap(id, ref, res => Tags(res.value.tags))
                          },
                          // Tag a resolver
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeWrite {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, resolvers.tag(id, ref, tag, tagRev, rev).map(_.void))
                              }
                            }
                          }
                        )
                      }
                    },
                    idSegment { resourceSegment =>
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}/{resourceId}") {
                        resolve(resourceSegment, ref, underscoreToOption(id))
                      }
                    }
                  )
                }
              )
            }
          )
        }
      }
    }

  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](id: IdSegment, ref: ProjectRef, f: ResolverResource => A)(implicit
      caller: Caller
  ): Route =
    authorizeFor(AclAddress.Project(ref), Read).apply {
      (parameter("rev".as[Long].?) & parameter("tag".as[TagLabel].?)) {
        case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
        case (Some(rev), _)     => emit(resolvers.fetchAt(id, ref, rev).map(f))
        case (_, Some(tag))     => emit(resolvers.fetchBy(id, ref, tag).map(f))
        case _                  => emit(resolvers.fetch(id, ref).map(f))
      }
    }

  private def resolve(resourceSegment: IdSegment, projectRef: ProjectRef, resolverId: Option[IdSegment])(implicit
      caller: Caller
  ): Route =
    authorizeFor(AclAddress.Project(projectRef), Permissions.resources.read).apply {
      parameter("showReport".as[Boolean].withDefault(default = false)) { showReport =>
        resolverId match {
          case Some(r) =>
            implicit val resultEncoder: JsonLdEncoder[MultiResolutionResult[ResolverReport]] =
              multiResolutionJsonLdEncoder[ResolverReport](showReport)
            emit(multiResolution(resourceSegment, projectRef, r))
          case None    =>
            implicit val resultEncoder: JsonLdEncoder[MultiResolutionResult[ResourceResolutionReport]] =
              multiResolutionJsonLdEncoder[ResourceResolutionReport](showReport)
            emit(multiResolution(resourceSegment, projectRef).leftWiden[ResolverRejection])
        }
      }
    }

}

object ResolversRoutes {

  /**
    * @return the [[Route]] for resolvers
    */
  def apply(
      identities: Identities,
      acls: Acls,
      projects: Projects,
      resolvers: Resolvers,
      multiResolution: MultiResolution
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ResolversRoutes(identities, acls, projects, resolvers, multiResolution).routes

}
