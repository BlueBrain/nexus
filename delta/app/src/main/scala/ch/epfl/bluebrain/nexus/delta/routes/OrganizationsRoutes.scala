package ch.epfl.bluebrain.nexus.delta.routes

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive, Directive1, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes.OrganizationInput
import ch.epfl.bluebrain.nexus.delta.routes.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.HttpResponseFields._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{Organization, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
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
  * @param identities    the identities operations bundle
  * @param organizations the organizations operations bundle
  * @param acls          the acls operations bundle
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
  implicit val orgContext: ContextValue = Organization.context

  private def orgsSearchParams: Directive1[OrganizationSearchParams] =
    searchParams.tmap { case (deprecated, rev, createdBy, updatedBy) =>
      OrganizationSearchParams(deprecated, rev, createdBy, updatedBy)
    }

  private def fetchByUUID(uuid: UUID, permission: Permission)(implicit
      caller: Caller
  ): Directive1[OrganizationResource] =
    onSuccess(organizations.fetch(uuid).runToFuture).flatMap {
      case Some(org) => authorizeFor(AclAddress.Organization(org.value.label), permission).tmap(_ => org)
      case None      => failWith(AuthorizationFailed)
    }

  private def fetchByUUIDAndRev(uuid: UUID, permission: Permission, rev: Long)(implicit
      caller: Caller
  ): Directive1[OrganizationResource] =
    onSuccess(organizations.fetchAt(uuid, rev).leftWiden[OrganizationRejection].attempt.runToFuture).flatMap {
      case Right(Some(org)) => authorizeFor(AclAddress.Organization(org.value.label), permission).tmap(_ => org)
      case Right(None)      => failWith(AuthorizationFailed)
      case Left(r)          => Directive(_ => discardEntityAndEmit(r))
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("orgs") {
          concat(
            // List organizations
            (get & extractUri & paginated & orgsSearchParams & pathEndOrSingleSlash) { (uri, pagination, params) =>
              operationName(s"$prefixSegment/orgs") {
                implicit val searchEncoder: SearchEncoder[OrganizationResource] = searchResultsEncoder(pagination, uri)
                emit(organizations.list(pagination, params))
              }
            },
            // SSE organizations
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/orgs/events") {
                authorizeFor(AclAddress.Root, events.read).apply {
                  lastEventId { offset =>
                    emit(organizations.events(offset))
                  }
                }
              }
            },
            (label & pathEndOrSingleSlash) { id =>
              operationName(s"$prefixSegment/orgs/{label}") {
                concat(
                  put {
                    authorizeFor(AclAddress.Organization(id), orgs.write).apply {
                      parameter("rev".as[Long].?) {
                        case Some(rev) =>
                          // Update organization
                          entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                            emit(organizations.update(id, description, rev).map(_.void))
                          }
                        case None      =>
                          // Create organization
                          entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                            emit(StatusCodes.Created, organizations.create(id, description).map(_.void))
                          }
                      }
                    }
                  },
                  get {
                    authorizeFor(AclAddress.Organization(id), orgs.read).apply {
                      parameter("rev".as[Long].?) {
                        case Some(rev) => // Fetch organization at specific revision
                          emit(organizations.fetchAt(id, rev).leftWiden[OrganizationRejection])
                        case None      => // Fetch organization
                          emit(organizations.fetch(id))

                      }
                    }
                  },
                  // Deprecate organization
                  delete {
                    authorizeFor(AclAddress.Organization(id), orgs.write).apply {
                      parameter("rev".as[Long]) { rev => emit(organizations.deprecate(id, rev).map(_.void)) }
                    }
                  }
                )
              }
            },
            (uuid & pathEndOrSingleSlash) { uuid =>
              operationName(s"$prefixSegment/orgs/{uuid}") {
                get {
                  parameter("rev".as[Long].?) {
                    case Some(rev) => // Fetch organization from UUID at specific revision
                      fetchByUUIDAndRev(uuid, orgs.read, rev).apply { org =>
                        emit(org)
                      }
                    case None      => // Fetch organization from UUID
                      fetchByUUID(uuid, orgs.read).apply { org =>
                        emit(org)
                      }
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
    * @return the [[Route]] for organizations
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
