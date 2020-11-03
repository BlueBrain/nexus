package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes.RealmInput
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes.RealmInput._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{Realm, RealmRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Lens, RealmResource, Realms}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

class RealmsRoutes(identities: Identities, realms: Realms, acls: Acls)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri._

  private val realmsIri = endpoint.toIri / "realms"

  implicit val iriLens: Lens[Label, Iri]  = (l: Label) => realmsIri / l.value
  implicit val realmContext: ContextValue = Realm.context

  private def realmsSearchParams: Directive1[RealmSearchParams] =
    searchParams.tmap { case (deprecated, rev, createdBy, updatedBy) =>
      RealmSearchParams(None, deprecated, rev, createdBy, updatedBy)
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractSubject { implicit subject =>
        pathPrefix("realms") {
          concat(
            // List realms
            (get & extractUri & paginated & realmsSearchParams & pathEndOrSingleSlash) { (uri, pagination, params) =>
              operationName(s"$prefixSegment/realms") {
                implicit val searchEncoder: SearchEncoder[RealmResource] = searchResourceEncoder(pagination, uri)
                completeSearch(realms.list(pagination, params))
              }
            },
            // SSE realms
            (pathPrefix("events") & pathEndOrSingleSlash) {
              operationName(s"$prefixSegment/realms/events") {
                lastEventId { offset =>
                  completeStream(realms.events(offset))
                }
              }
            },
            (label & pathEndOrSingleSlash) { id =>
              operationName(s"$prefixSegment/realms/{label}") {
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        // Update realm
                        entity(as[RealmInput]) { case RealmInput(name, openIdConfig, logo) =>
                          completeIO(realms.update(id, rev, name, openIdConfig, logo))
                        }
                      case None      =>
                        // Create realm
                        entity(as[RealmInput]) { case RealmInput(name, openIdConfig, logo) =>
                          completeIO(StatusCodes.Created, realms.create(id, name, openIdConfig, logo))
                        }
                    }
                  },
                  get {
                    parameter("rev".as[Long].?) {
                      case Some(rev) => // Fetch realm at specific revision
                        completeIOOpt(realms.fetchAt(id, rev).leftMap[RealmRejection](identity))
                      case None      => // Fetch realm
                        completeUIOOpt(realms.fetch(id))
                    }
                  },
                  // Deprecate realm
                  delete {
                    parameter("rev".as[Long]) { rev => completeIO(realms.deprecate(id, rev)) }
                  }
                )
              }
            }
          )
        }
      }
    }
}

object RealmsRoutes {
  import ch.epfl.bluebrain.nexus.delta.sdk.instances._

  final private[routes] case class RealmInput(name: Name, openIdConfig: Uri, logo: Option[Uri])
  private[routes] object RealmInput {
    implicit val realmDecoder: Decoder[RealmInput] = deriveDecoder[RealmInput]
  }

  /**
    * @return the [[Route]] for realms
    */
  def apply(identities: Identities, realms: Realms, acls: Acls)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new RealmsRoutes(identities, realms, acls).routes

}
