package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes.OrganizationInput
import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{Organization, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.{OrganizationDeleter, Organizations}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions._
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName

import scala.annotation.nowarn

/**
  * The organization routes.
  *
  * @param identities
  *   the identities operations bundle
  * @param organizations
  *   the organizations operations bundle
  * @param aclCheck
  *   verify the acl for users
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
final class OrganizationsRoutes(
    identities: Identities,
    organizations: Organizations,
    orgDeleter: OrganizationDeleter,
    aclCheck: AclCheck,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import baseUri.prefixSegment
  import schemeDirectives._

  private def orgsSearchParams(implicit caller: Caller): Directive1[OrganizationSearchParams] =
    (searchParams & parameter("label".?)).tmap { case (deprecated, rev, createdBy, updatedBy, label) =>
      val fetchAllCached = aclCheck.fetchAll.memoizeOnSuccess
      OrganizationSearchParams(
        deprecated,
        rev,
        createdBy,
        updatedBy,
        label,
        org => aclCheck.authorizeFor(org.label, orgs.read, fetchAllCached)
      )
    }

  private def emitMetadata(value: IO[OrganizationResource]) = {
    emit(
      value
        .mapValue(_.metadata)
        .attemptNarrow[OrganizationRejection]
    )
  }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("orgs") {
        extractCaller { implicit caller =>
          concat(
            // List organizations
            (get & extractUri & fromPaginated & orgsSearchParams & sort[Organization] & pathEndOrSingleSlash) {
              (uri, pagination, params, order) =>
                operationName(s"$prefixSegment/orgs") {
                  implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[OrganizationResource]] =
                    searchResultsJsonLdEncoder(Organization.context, pagination, uri)

                  emit(
                    organizations
                      .list(pagination, params, order)
                      .widen[SearchResults[OrganizationResource]]
                      .attemptNarrow[OrganizationRejection]
                  )
                }
            },
            (resolveOrg & pathEndOrSingleSlash) { id =>
              operationName(s"$prefixSegment/orgs/{label}") {
                concat(
                  put {
                    parameter("rev".as[Int]) { rev =>
                      authorizeFor(id, orgs.write).apply {
                        // Update organization
                        entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                          emitMetadata(
                            organizations
                              .update(id, description, rev)
                          )
                        }
                      }
                    }
                  },
                  get {
                    authorizeFor(id, orgs.read).apply {
                      parameter("rev".as[Int].?) {
                        case Some(rev) => // Fetch organization at specific revision
                          emit(organizations.fetchAt(id, rev).attemptNarrow[OrganizationRejection])
                        case None      => // Fetch organization
                          emit(organizations.fetch(id).attemptNarrow[OrganizationRejection])

                      }
                    }
                  },
                  // Deprecate or delete organization
                  delete {
                    concat(
                      parameter("rev".as[Int]) { rev =>
                        authorizeFor(id, orgs.write).apply {
                          emitMetadata(
                            organizations.deprecate(id, rev)
                          )
                        }
                      },
                      parameter("prune".requiredValue(true)) { _ =>
                        authorizeFor(id, orgs.delete).apply {
                          emit(orgDeleter.delete(id).attemptNarrow[OrganizationRejection])
                        }
                      }
                    )
                  }
                )
              }
            },
            (label & pathEndOrSingleSlash) { label =>
              operationName(s"$prefixSegment/orgs/{label}") {
                (put & authorizeFor(label, orgs.create)) {
                  // Create organization
                  entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                    val response: IO[Either[OrganizationRejection, ResourceF[Organization.Metadata]]] =
                      organizations.create(label, description).mapValue(_.metadata).attemptNarrow[OrganizationRejection]
                    emit(
                      StatusCodes.Created,
                      response
                    )
                  }
                }
              }
            }
          )
        }
      }
    }
}

object OrganizationsRoutes {
  final private[routes] case class OrganizationInput(description: Option[String])

  private[routes] object OrganizationInput {
    @nowarn("cat=unused")
    implicit final private val configuration: Configuration      = Configuration.default.withStrictDecoding
    implicit val organizationDecoder: Decoder[OrganizationInput] = deriveConfiguredDecoder[OrganizationInput]
  }

  /**
    * @return
    *   the [[Route]] for organizations
    */
  def apply(
      identities: Identities,
      organizations: Organizations,
      orgDeleter: OrganizationDeleter,
      aclCheck: AclCheck,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new OrganizationsRoutes(identities, organizations, orgDeleter, aclCheck, schemeDirectives).routes

}
