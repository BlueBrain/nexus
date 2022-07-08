package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resolvers.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * The resolver routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   verify the acls for users
  * @param resolvers
  *   the resolvers module
  * @param schemeDirectives
  *   directives related to orgs and projects
  * @param index
  *   the indexing action on write operations
  */
final class ResolversRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    resolvers: Resolvers,
    multiResolution: MultiResolution,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment
  import schemeDirectives._

  implicit private val fetchProjectUuids: FetchUuids = _ => UIO.none

  implicit private val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.resolversMetadata))

  private def resolverSearchParams(implicit projectRef: ProjectRef, caller: Caller): Directive1[ResolverSearchParams] =
    (searchParams & types).tmap { case (deprecated, rev, createdBy, updatedBy, types) =>
      val fetchAllCached = aclCheck.fetchAll.memoizeOnSuccess
      ResolverSearchParams(
        Some(projectRef),
        deprecated,
        rev,
        createdBy,
        updatedBy,
        types,
        resolver => aclCheck.authorizeFor(resolver.project, Read, fetchAllCached)
      )
    }

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("resolvers", schemas.resolvers)) {
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
            (resolveOrg & pathPrefix("events") & pathEndOrSingleSlash) { org =>
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
            resolveProjectRef.apply { implicit ref =>
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
                  (post & noParameter("rev") & entity(as[Json]) & indexingMode) { (payload, mode) =>
                    authorizeWrite {
                      emit(Created, resolvers.create(ref, payload).tapEval(index(ref, _, mode)).map(_.void))
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
                (idSegment & indexingMode) { (id, mode) =>
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
                                    resolvers.create(id, ref, payload).tapEval(index(ref, _, mode)).map(_.void)
                                  )
                                case (Some(rev), payload) =>
                                  // Update a resolver
                                  emit(resolvers.update(id, ref, rev, payload).tapEval(index(ref, _, mode)).map(_.void))
                              }
                            }
                          },
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeWrite {
                              // Deprecate a resolver
                              emit(
                                resolvers
                                  .deprecate(id, ref, rev)
                                  .tapEval(index(ref, _, mode))
                                  .map(_.void)
                                  .rejectOn[ResolverNotFound]
                              )
                            }
                          },
                          // Fetches a resolver
                          (get & idSegmentRef(id)) { id =>
                            emitOrFusionRedirect(
                              ref,
                              id,
                              authorizeRead {
                                emit(resolvers.fetch(id, ref).rejectOn[ResolverNotFound])
                              }
                            )
                          }
                        )
                      }
                    },
                    // Fetches a resolver original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id) & authorizeRead) { id =>
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}/source") {
                        emit(resolvers.fetch(id, ref).map(_.value.source).rejectOn[ResolverNotFound])
                      }
                    },
                    // Tags
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a resolver tags
                          (get & idSegmentRef(id) & authorizeRead) { id =>
                            emit(resolvers.fetch(id, ref).map(res => Tags(res.value.tags)).rejectOn[ResolverNotFound])
                          },
                          // Tag a resolver
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeWrite {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(
                                  Created,
                                  resolvers.tag(id, ref, tag, tagRev, rev).tapEval(index(ref, _, mode)).map(_.void)
                                )
                              }
                            }
                          }
                        )
                      }
                    },
                    // Fetch a resource using a resolver
                    (idSegmentRef & pathEndOrSingleSlash) { resourceIdRef =>
                      operationName(s"$prefixSegment/resolvers/{org}/{project}/{id}/{resourceId}") {
                        resolve(resourceIdRef, ref, underscoreToOption(id))
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

  private def resolve(resourceSegment: IdSegmentRef, projectRef: ProjectRef, resolverId: Option[IdSegment])(implicit
      caller: Caller
  ): Route =
    authorizeFor(projectRef, Permissions.resources.read).apply {
      parameter("showReport".as[Boolean].withDefault(default = false)) { showReport =>
        def emitResult[R: JsonLdEncoder](io: IO[ResolverRejection, MultiResolutionResult[R]]) =
          if (showReport)
            emit(io.map(_.report))
          else
            emit(io.map(_.value.jsonLdValue))

        resolverId.fold(emitResult(multiResolution(resourceSegment, projectRef))) { resolverId =>
          emitResult(multiResolution(resourceSegment, projectRef, resolverId))
        }
      }
    }

}

object ResolversRoutes {

  /**
    * @return
    *   the [[Route]] for resolvers
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      resolvers: Resolvers,
      multiResolution: MultiResolution,
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ResolversRoutes(identities, aclCheck, resolvers, multiResolution, schemeDirectives, index).routes

}
