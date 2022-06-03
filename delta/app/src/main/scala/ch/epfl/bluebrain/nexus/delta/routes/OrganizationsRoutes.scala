package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{parameter, _}
import akka.http.scaladsl.server.{Directive1, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes.OrganizationInput
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.AnyOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{Organization, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, OrganizationResource, Organizations}
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

import scala.annotation.nowarn

/**
  * The organization routes.
  *
  * @param identities
  *   the identities operations bundle
  * @param organizations
  *   the organizations operations bundle
  * @param acls
  *   the acls operations bundle
  */
final class OrganizationsRoutes(identities: Identities, organizations: Organizations, acls: Acls)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  private def orgsSearchParams(implicit caller: Caller): Directive1[OrganizationSearchParams] =
    (searchParams & parameter("label".?)).tflatMap { case (deprecated, rev, createdBy, updatedBy, label) =>
      onSuccess(acls.listSelf(AnyOrganization(true)).runToFuture).map { aclsCol =>
        OrganizationSearchParams(
          deprecated,
          rev,
          createdBy,
          updatedBy,
          label,
          org => aclsCol.exists(caller.identities, orgs.read, org.label)
        )
      }
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

                  emit(organizations.list(pagination, params, order).widen[SearchResults[OrganizationResource]])
                }
            },
            // SSE organizations
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/orgs/events") {
                authorizeFor(AclAddress.Root, events.read).apply {
                  failWith(new NotImplementedError("TODO: Handle SSEs"))
//                  lastEventId { offset =>
//                    emit(organizations.events(offset))
//                  }
                }
              }
            },
            (orgLabel(organizations) & pathEndOrSingleSlash) { id =>
              operationName(s"$prefixSegment/orgs/{label}") {
                concat(
                  put {
                    parameter("rev".as[Int]) { rev =>
                      authorizeFor(id, orgs.write).apply {
                        // Update organization
                        entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                          emit(organizations.update(id, description, rev).mapValue(_.metadata))
                        }
                      }
                    }
                  },
                  get {
                    authorizeFor(id, orgs.read).apply {
                      parameter("rev".as[Int].?) {
                        case Some(rev) => // Fetch organization at specific revision
                          emit(organizations.fetchAt(id, rev).leftWiden[OrganizationRejection])
                        case None      => // Fetch organization
                          emit(organizations.fetch(id).leftWiden[OrganizationRejection])

                      }
                    }
                  },
                  // Deprecate organization
                  delete {
                    authorizeFor(id, orgs.write).apply {
                      parameter("rev".as[Int]) { rev => emit(organizations.deprecate(id, rev).mapValue(_.metadata)) }
                    }
                  }
                )
              }
            },
            (label & pathEndOrSingleSlash) { label =>
              operationName(s"$prefixSegment/orgs/{label}") {
                (put & authorizeFor(label, orgs.create)) {
                  // Create organization
                  entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                    emit(StatusCodes.Created, organizations.create(label, description).mapValue(_.metadata))
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
  def apply(identities: Identities, organizations: Organizations, acls: Acls)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new OrganizationsRoutes(identities, organizations, acls).routes

}
