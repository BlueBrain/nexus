package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes.OrganizationInput
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{Organization, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, IriResolver, OrganizationResource, Organizations}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The organization routes.
  *
  * @param identities    the identities operations bundle
  * @param organizations the organizations operations bundle
  */
final class OrganizationsRoutes(identities: Identities, organizations: Organizations)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri._

  private val orgsIri = endpoint.toIri / "orgs"

  implicit val iriResolver: IriResolver[Label] = (l: Label) => orgsIri / l.value
  implicit val orgContext: ContextValue        = Organization.context

  private def orgsSearchParams: Directive1[OrganizationSearchParams] =
    searchParams.tmap { case (deprecated, rev, createdBy, updatedBy) =>
      OrganizationSearchParams(deprecated, rev, createdBy, updatedBy)
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractSubject { implicit subject =>
        pathPrefix("orgs") {
          concat(
            // List organizations
            (get & extractUri & paginated & orgsSearchParams & pathEndOrSingleSlash) { (uri, pagination, params) =>
              operationName(s"$prefixSegment/orgs") {
                implicit val searchEncoder: SearchEncoder[OrganizationResource] = searchResourceEncoder(pagination, uri)
                completeSearch(organizations.list(pagination, params))
              }
            },
            // SSE organizations
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/orgs/events") {
                lastEventId { offset =>
                  completeStream(organizations.events(offset))
                }
              }
            },
            (label & pathEndOrSingleSlash) { id =>
              operationName(s"$prefixSegment/orgs/{label}") {
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        // Update organization
                        entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                          completeIO(organizations.update(id, description, rev))
                        }
                      case None      =>
                        // Create organization
                        entity(as[OrganizationInput]) { case OrganizationInput(description) =>
                          completeIO(StatusCodes.Created, organizations.create(id, description))
                        }
                    }
                  },
                  get {
                    parameter("rev".as[Long].?) {
                      case Some(rev) => // Fetch organization at specific revision
                        completeIOOpt(organizations.fetchAt(id, rev).leftMap[OrganizationRejection](identity))
                      case None      => // Fetch organization
                        completeUIOOpt(organizations.fetch(id))
                    }
                  },
                  // Deprecate organization
                  delete {
                    parameter("rev".as[Long]) { rev => completeIO(organizations.deprecate(id, rev)) }
                  }
                )
              }
            },
            (uuid & pathEndOrSingleSlash) { uuid =>
              operationName(s"$prefixSegment/orgs/{uuid}") {
                get {
                  parameter("rev".as[Long].?) {
                    case Some(rev) => // Fetch organization from UUID at specific revision
                      completeIOOpt(organizations.fetchAt(uuid, rev).leftMap[OrganizationRejection](identity))
                    case None      => // Fetch organization from UUID
                      completeUIOOpt(organizations.fetch(uuid))
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
    implicit val organizationDecoder: Decoder[OrganizationInput] = deriveDecoder[OrganizationInput]
  }

  /**
    * @return the [[Route]] for organizations
    */
  def apply(identities: Identities, organizations: Organizations)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new OrganizationsRoutes(identities, organizations).routes

}
