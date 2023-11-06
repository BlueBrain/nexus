package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resolvers.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{MultiResolutionResult, Resolver, ResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{MultiResolution, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, Printer}

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
  * @param indexAction
  *   the indexing action on write operations
  */
final class ResolversRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    resolvers: Resolvers,
    multiResolution: MultiResolution,
    schemeDirectives: DeltaSchemeDirectives,
    indexAction: IndexingAction.Execute[Resolver]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives._

  implicit private val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.resolversMetadata))

  private def emitFetch(io: IO[ResolverResource]): Route                            =
    emit(io.attemptNarrow[ResolverRejection].rejectOn[ResolverNotFound])
  private def emitMetadata(statusCode: StatusCode, io: IO[ResolverResource]): Route =
    emit(statusCode, io.map(_.void).attemptNarrow[ResolverRejection])

  private def emitMetadata(io: IO[ResolverResource]): Route = emitMetadata(StatusCodes.OK, io)

  private def emitMetadataOrReject(io: IO[ResolverResource]): Route =
    emit(io.map(_.void).attemptNarrow[ResolverRejection].rejectOn[ResolverNotFound])

  private def emitSource(io: IO[ResolverResource]): Route = {
    implicit val source: Printer = sourcePrinter
    emit(io.map(_.value.source).attemptNarrow[ResolverRejection].rejectOn[ResolverNotFound])
  }

  private def emitTags(io: IO[ResolverResource]): Route =
    emit(io.map(_.value.tags).attemptNarrow[ResolverRejection].rejectOn[ResolverNotFound])

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("resolvers", schemas.resolvers)) {
      pathPrefix("resolvers") {
        extractCaller { implicit caller =>
          (resolveProjectRef & indexingMode) { (ref, mode) =>
            def index(resolver: ResolverResource): IO[Unit] = indexAction(resolver.value.project, resolver, mode)
            val authorizeRead                               = authorizeFor(ref, Read)
            val authorizeWrite                              = authorizeFor(ref, Write)
            concat(
              pathEndOrSingleSlash {
                // Create a resolver without an id segment
                (post & noParameter("rev") & entity(as[Json])) { payload =>
                  authorizeWrite {
                    emitMetadata(Created, resolvers.create(ref, payload).flatTap(index))
                  }
                }
              },
              idSegment { id =>
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      put {
                        authorizeWrite {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, payload)      =>
                              // Create a resolver with an id segment
                              emitMetadata(Created, resolvers.create(id, ref, payload).flatTap(index))
                            case (Some(rev), payload) =>
                              // Update a resolver
                              emitMetadata(resolvers.update(id, ref, rev, payload).flatTap(index))
                          }
                        }
                      },
                      (delete & parameter("rev".as[Int])) { rev =>
                        authorizeWrite {
                          // Deprecate a resolver
                          emitMetadataOrReject(resolvers.deprecate(id, ref, rev).flatTap(index))
                        }
                      },
                      // Fetches a resolver
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          ref,
                          id,
                          authorizeRead {
                            emitFetch(resolvers.fetch(id, ref))
                          }
                        )
                      }
                    )
                  },
                  // Fetches a resolver original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id) & authorizeRead) { id =>
                    emitSource(resolvers.fetch(id, ref))
                  },
                  // Tags
                  (pathPrefix("tags") & pathEndOrSingleSlash) {
                    concat(
                      // Fetch a resolver tags
                      (get & idSegmentRef(id) & authorizeRead) { id =>
                        emitTags(resolvers.fetch(id, ref))
                      },
                      // Tag a resolver
                      (post & parameter("rev".as[Int])) { rev =>
                        authorizeWrite {
                          entity(as[Tag]) { case Tag(tagRev, tag) =>
                            emitMetadata(Created, resolvers.tag(id, ref, tag, tagRev, rev).flatTap(index))
                          }
                        }
                      }
                    )
                  },
                  // Fetch a resource using a resolver
                  (idSegmentRef & pathEndOrSingleSlash) { resourceIdRef =>
                    resolve(resourceIdRef, ref, underscoreToOption(id))
                  }
                )
              }
            )
          }
        }
      }
    }

  private def resolve(resourceSegment: IdSegmentRef, projectRef: ProjectRef, resolverId: Option[IdSegment])(implicit
      caller: Caller
  ): Route =
    authorizeFor(projectRef, Permissions.resources.read).apply {
      parameter("showReport".as[Boolean].withDefault(default = false)) { showReport =>
        def emitResult[R: JsonLdEncoder](io: IO[MultiResolutionResult[R]]) =
          if (showReport)
            emit(io.map(_.report).attemptNarrow[ResolverRejection])
          else
            emit(io.map(_.value.jsonLdValue).attemptNarrow[ResolverRejection])

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
      index: IndexingAction.Execute[Resolver]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ResolversRoutes(identities, aclCheck, resolvers, multiResolution, schemeDirectives, index).routes

}
