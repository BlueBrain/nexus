package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resolvers.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.searchParams
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

/**
  * The resolver routes
  *
  * @param identities    the identity module
  * @param acls          the acls module
  * @param organizations the organizations module
  * @param projects      the projects module
  * @param resolvers     the resolvers module
  */
final class ResolversRoutes(
    identities: Identities,
    acls: Acls,
    organizations: Organizations,
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
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids = projects

  implicit private val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.resolversMetadata))

  private def resolverSearchParams(implicit projectRef: ProjectRef, caller: Caller): Directive1[ResolverSearchParams] =
    (searchParams & types(projects)).tflatMap { case (deprecated, rev, createdBy, updatedBy, types) =>
      callerAcls.map { aclsCol =>
        ResolverSearchParams(
          Some(projectRef),
          deprecated,
          rev,
          createdBy,
          updatedBy,
          types,
          resolver => aclsCol.exists(caller.identities, Read, resolver.project)
        )
      }
    }

  // TODO: SSE missing for resolver events for all organization and for a particular project
  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("resolvers", schemas.resolvers, projects)) {
      pathPrefix("resolvers") {
        extractCaller { implicit caller =>
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
            // SSE resolvers for all events belonging to an organization
            (orgLabel(organizations) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/resolvers/{org}/events") {
                  authorizeFor(org, events.read).apply {
                    lastEventId { offset =>
                      emit(resolvers.events(org, offset).leftWiden[ResolverRejection])
                    }
                  }
                }
              }
            },
            projectRef(projects).apply { implicit ref =>
              val projectAddress = ref
              val authorizeRead  = authorizeFor(projectAddress, Read)
              val authorizeEvent = authorizeFor(projectAddress, events.read)
              val authorizeWrite = authorizeFor(projectAddress, Write)
              concat(
                // SSE resolvers for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  get {
                    operationName(s"$prefixSegment/resolvers/{org}/{project}/events") {
                      authorizeEvent {
                        lastEventId { offset =>
                          emit(resolvers.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                (pathEndOrSingleSlash & operationName(s"$prefixSegment/resolvers/{org}/{project}")) {
                  // Create a resolver without an id segment
                  (post & noParameter("rev") & entity(as[Json])) { payload =>
                    authorizeWrite {
                      emit(Created, resolvers.create(ref, payload).map(_.void))
                    }
                  }
                },
                (pathPrefix("caches") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/resolvers/{org}/{project}/caches") {
                    // List resolvers in cache
                    (get & extractUri & fromPaginated & resolverSearchParams & sort[Resolver]) {
                      (uri, pagination, params, order) =>
                        authorizeRead {
                          implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ResolverResource]] =
                            searchResultsJsonLdEncoder(Resolver.context, pagination, uri)

                          emit(resolvers.list(pagination, params, order).widen[SearchResults[ResolverResource]])
                        }
                    }
                  }
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
                        fetchSource(id, ref, _.value.source)
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
                    // Fetch a resource using a resolver
                    (idSegment & pathEndOrSingleSlash) { resourceSegment =>
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

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: ResolverResource => A
  )(implicit caller: Caller) =
    authorizeFor(ref, Read).apply {
      fetchResource(
        rev => emit(resolvers.fetchAt(id, ref, rev).map(f).rejectOn[ResolverNotFound]),
        tag => emit(resolvers.fetchBy(id, ref, tag).map(f).rejectOn[ResolverNotFound]),
        emit(resolvers.fetch(id, ref).map(f).rejectOn[ResolverNotFound])
      )
    }

  private def fetchSource(id: IdSegment, ref: ProjectRef, f: ResolverResource => Json)(implicit caller: Caller) =
    authorizeFor(ref, Read).apply {
      fetchResource(
        rev => emit(resolvers.fetchAt(id, ref, rev).map(f).rejectOn[ResolverNotFound]),
        tag => emit(resolvers.fetchBy(id, ref, tag).map(f).rejectOn[ResolverNotFound]),
        emit(resolvers.fetch(id, ref).map(f).rejectOn[ResolverNotFound])
      )
    }

  private def resolve(resourceSegment: IdSegment, projectRef: ProjectRef, resolverId: Option[IdSegment])(implicit
      caller: Caller
  ): Route =
    authorizeFor(projectRef, Permissions.resources.read).apply {
      parameter("showReport".as[Boolean].withDefault(default = false)) { showReport =>
        def emitResult[R: JsonLdEncoder](io: IO[ResolverRejection, MultiResolutionResult[R]]) =
          if (showReport)
            emit(io.map(_.report))
          else
            emit(io.map(_.value.jsonLdValue))

        resolverId match {
          case Some(r) =>
            fetchResource(
              rev => emitResult(multiResolution(resourceSegment, projectRef, Some(Left(rev)), r)),
              tag => emitResult(multiResolution(resourceSegment, projectRef, Some(Right(tag)), r)),
              emitResult(multiResolution(resourceSegment, projectRef, None, r))
            )
          case None    =>
            fetchResource(
              rev => emitResult(multiResolution(resourceSegment, projectRef, Some(Left(rev)))),
              tag => emitResult(multiResolution(resourceSegment, projectRef, Some(Right(tag)))),
              emitResult(multiResolution(resourceSegment, projectRef, None))
            )
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
      organizations: Organizations,
      projects: Projects,
      resolvers: Resolvers,
      multiResolution: MultiResolution
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ResolversRoutes(identities, acls, organizations, projects, resolvers, multiResolution).routes

}
