package ai.senscience.nexus.delta.routes

import ai.senscience.nexus.delta.routes.OrganizationsRoutes.OrganizationInput
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Route}
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.*
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{Organization, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.{OrganizationDeleter, Organizations}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

/**
  * The organization routes.
  *
  * @param identities
  *   the identities operations bundle
  * @param organizations
  *   the organizations operations bundle
  * @param aclCheck
  *   verify the acl for users
  */
final class OrganizationsRoutes(
    identities: Identities,
    organizations: Organizations,
    orgDeleter: OrganizationDeleter,
    aclCheck: AclCheck
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  private def orgsSearchParams(implicit caller: Caller): Directive1[OrganizationSearchParams] =
    (searchParams & parameter("label".?)).tmap { case (deprecated, rev, createdBy, updatedBy, label) =>
      OrganizationSearchParams(
        deprecated,
        rev,
        createdBy,
        updatedBy,
        label,
        org => aclCheck.authorizeFor(org.label, orgs.read)
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
            (get & extractHttp4sUri & fromPaginated & orgsSearchParams & sort[Organization] & pathEndOrSingleSlash) {
              (uri, pagination, params, order) =>
                implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[OrganizationResource]] =
                  searchResultsJsonLdEncoder(Organization.context, pagination, uri)

                emit(
                  organizations
                    .list(pagination, params, order)
                    .widen[SearchResults[OrganizationResource]]
                    .attemptNarrow[OrganizationRejection]
                )
            },
            label.apply { org =>
              concat(
                pathEndOrSingleSlash {
                  concat(
                    put {
                      parameter("rev".as[Int]) { rev =>
                        authorizeFor(org, orgs.write).apply {
                          // Update organization
                          entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                            emitMetadata(
                              organizations
                                .update(org, description, rev)
                            )
                          }
                        }
                      }
                    },
                    get {
                      authorizeFor(org, orgs.read).apply {
                        parameter("rev".as[Int].?) {
                          case Some(rev) => // Fetch organization at specific revision
                            emit(organizations.fetchAt(org, rev).attemptNarrow[OrganizationRejection])
                          case None      => // Fetch organization
                            emit(organizations.fetch(org).attemptNarrow[OrganizationRejection])

                        }
                      }
                    },
                    // Deprecate or delete organization
                    delete {
                      parameters("rev".as[Int].?, "prune".as[Boolean].?) {
                        case (Some(rev), None)        => deprecate(org, rev)
                        case (Some(rev), Some(false)) => deprecate(org, rev)
                        case (None, Some(true))       =>
                          authorizeFor(org, orgs.delete).apply {
                            emit(orgDeleter.apply(org).attemptNarrow[OrganizationRejection])
                          }
                        case (_, _)                   => emit((InvalidDeleteRequest(org): OrganizationRejection).asLeft[Unit].pure[IO])
                      }
                    }
                  )
                },
                (put & pathPrefix("undeprecate") & pathEndOrSingleSlash & parameter("rev".as[Int])) { rev =>
                  authorizeFor(org, orgs.write).apply {
                    emitMetadata(organizations.undeprecate(org, rev))
                  }
                }
              )
            },
            (label & pathEndOrSingleSlash) { label =>
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
          )
        }
      }
    }

  private def deprecate(id: Label, rev: Int)(implicit c: Caller) =
    authorizeFor(id, orgs.write).apply {
      emitMetadata(organizations.deprecate(id, rev))
    }
}

object OrganizationsRoutes {
  final private[routes] case class OrganizationInput(description: Option[String])

  private[routes] object OrganizationInput {

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
      aclCheck: AclCheck
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new OrganizationsRoutes(identities, organizations, orgDeleter, aclCheck).routes

}
