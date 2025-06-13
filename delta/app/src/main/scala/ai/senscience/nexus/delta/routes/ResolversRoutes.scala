package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.ResolutionType.{AllResolversInProject, SingleResolver}
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.*
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{HttpResponseFields, OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resolvers.{read as Read, write as Write}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResolverNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{MultiResolutionResult, Resolver, ResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{MultiResolution, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

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
  */
final class ResolversRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    resolvers: Resolvers,
    multiResolution: MultiResolution,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives.*

  implicit private val resourceFUnitJsonLdEncoder: JsonLdEncoder[ResourceF[Unit]] =
    ResourceF.resourceFAJsonLdEncoder(ContextValue(contexts.resolversMetadata))

  private def emitFetch(io: IO[ResolverResource]): Route                            =
    emit(io.attemptNarrow[ResolverRejection].rejectOn[ResolverNotFound])
  private def emitMetadata(statusCode: StatusCode, io: IO[ResolverResource]): Route =
    emit(statusCode, io.map(_.void).attemptNarrow[ResolverRejection])

  private def emitMetadata(io: IO[ResolverResource]): Route = emitMetadata(StatusCodes.OK, io)

  private def emitMetadataOrReject(io: IO[ResolverResource]): Route =
    emit(io.map(_.void).attemptNarrow[ResolverRejection].rejectOn[ResolverNotFound])

  private def emitSource(io: IO[ResolverResource]): Route =
    emit(
      io
        .map { resource => OriginalSource(resource, resource.value.source) }
        .attemptNarrow[ResolverRejection]
        .rejectOn[ResolverNotFound]
    )

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("resolvers", schemas.resolvers)) {
      pathPrefix("resolvers") {
        extractCaller { implicit caller =>
          projectRef { project =>
            val authorizeRead  = authorizeFor(project, Read)
            val authorizeWrite = authorizeFor(project, Write)
            concat(
              // List resolvers
              pathEndOrSingleSlash {
                (get & authorizeRead) {
                  implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[ResolverResource]] =
                    searchResultsJsonLdEncoder(Resolver.context)
                  emit(resolvers.list(project).widen[SearchResults[ResolverResource]])
                }
              },
              pathEndOrSingleSlash {
                // Create a resolver without an id segment
                (post & noParameter("rev") & entity(as[Json])) { payload =>
                  authorizeWrite {
                    emitMetadata(Created, resolvers.create(project, payload))
                  }
                }
              },
              idSegment { resolver =>
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      put {
                        authorizeWrite {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, payload)      =>
                              // Create a resolver with an id segment
                              emitMetadata(Created, resolvers.create(resolver, project, payload))
                            case (Some(rev), payload) =>
                              // Update a resolver
                              emitMetadata(resolvers.update(resolver, project, rev, payload))
                          }
                        }
                      },
                      (delete & parameter("rev".as[Int])) { rev =>
                        authorizeWrite {
                          // Deprecate a resolver
                          emitMetadataOrReject(resolvers.deprecate(resolver, project, rev))
                        }
                      },
                      // Fetches a resolver
                      (get & idSegmentRef(resolver)) { resolverRef =>
                        emitOrFusionRedirect(
                          project,
                          resolverRef,
                          authorizeRead {
                            emitFetch(resolvers.fetch(resolverRef, project))
                          }
                        )
                      }
                    )
                  },
                  // Fetches a resolver original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(resolver) & authorizeRead) {
                    resolverRef =>
                      emitSource(resolvers.fetch(resolverRef, project))
                  },
                  // Fetch a resource using a resolver
                  idSegmentRef.apply { resourceIdRef =>
                    concat(
                      (pathEndOrSingleSlash & get) {
                        parameter("showReport".as[Boolean].withDefault(default = false)) { showReport =>
                          val outputType =
                            if (showReport) ResolvedResourceOutputType.Report else ResolvedResourceOutputType.JsonLd
                          resolveResource(resourceIdRef, project, resolutionType(resolver), outputType)
                        }
                      },
                      (pathPrefix("source") & pathEndOrSingleSlash & get & annotateSource) { annotate =>
                        resolveResource(
                          resourceIdRef,
                          project,
                          resolutionType(resolver),
                          if (annotate) ResolvedResourceOutputType.AnnotatedSource
                          else ResolvedResourceOutputType.Source
                        )
                      }
                    )
                  }
                )
              }
            )
          }
        }
      }
    }

  private def resolveResource(
      resource: IdSegmentRef,
      project: ProjectRef,
      resolutionType: ResolutionType,
      output: ResolvedResourceOutputType
  )(implicit baseUri: BaseUri, caller: Caller): Route =
    authorizeFor(project, Permissions.resources.read).apply {
      def emitResult[R: JsonLdEncoder: HttpResponseFields](io: IO[MultiResolutionResult[R]]) = {
        output match {
          case ResolvedResourceOutputType.Report          => emit(io.map(_.report).attemptNarrow[ResolverRejection])
          case ResolvedResourceOutputType.JsonLd          => emit(io.map(_.value.jsonLdValue).attemptNarrow[ResolverRejection])
          case ResolvedResourceOutputType.Source          =>
            emit(io.map(_.value.source).attemptNarrow[ResolverRejection])
          case ResolvedResourceOutputType.AnnotatedSource =>
            val annotatedSourceIO = io.map { r => OriginalSource.annotated(r.value.resource, r.value.source) }
            emit(annotatedSourceIO.attemptNarrow[ResolverRejection])
        }
      }

      resolutionType match {
        case ResolutionType.AllResolversInProject => emitResult(multiResolution(resource, project))
        case SingleResolver(resolver)             => emitResult(multiResolution(resource, project, resolver))
      }
    }

  private def resolutionType(segment: IdSegment): ResolutionType = {
    underscoreToOption(segment) match {
      case Some(resolver) => SingleResolver(resolver)
      case None           => AllResolversInProject
    }
  }
}

sealed trait ResolutionType
object ResolutionType {
  case object AllResolversInProject        extends ResolutionType
  case class SingleResolver(id: IdSegment) extends ResolutionType
}

sealed trait ResolvedResourceOutputType
object ResolvedResourceOutputType {
  case object Report          extends ResolvedResourceOutputType
  case object JsonLd          extends ResolvedResourceOutputType
  case object Source          extends ResolvedResourceOutputType
  case object AnnotatedSource extends ResolvedResourceOutputType
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
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ResolversRoutes(identities, aclCheck, resolvers, multiResolution, schemeDirectives).routes

}
