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
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, IriResolver, RealmResource, Realms}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.Decoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

class RealmsRoutes(identities: Identities, realms: Realms)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri._

  private val realmsIri = endpoint.toIri / "realms"

  implicit val iriResolver: IriResolver[Label] = (l: Label) => realmsIri / l.value
  implicit val realmContext: ContextValue      = Realm.context

  /**
    * @return the extracted search parameters from the request query parameters.
    */
  def searchParams: Directive1[RealmSearchParams] =
    (parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Long].?) &
      parameter("createdBy".as[Iri].?) &
      parameter("updatedBy".as[Iri].?)).tmap {
      case (deprecated, rev, createdBy, updatedBy) =>
        RealmSearchParams(None, deprecated, rev, createdBy, updatedBy)
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractSubject { implicit subject =>
        pathPrefix("realms") {
          concat(
            (get & extractUri & paginated & searchParams & pathEndOrSingleSlash) { (uri, pagination, params) =>
              operationName(s"$prefixSegment/realms") {
                implicit val searchEncoder: SearchEncoder[RealmResource] = searchResourceEncoder(pagination, uri)
                completeSearch(realms.list(pagination, params))
              }
            },
            (label & pathEndOrSingleSlash) { id =>
              operationName(s"/$prefixSegment/realms/{}") {
                concat(
                  put {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        entity(as[RealmInput]) {
                          case RealmInput(name, openIdConfig, logo) =>
                            completeIO(realms.update(id, rev, name, openIdConfig, logo))
                        }
                      case None      =>
                        entity(as[RealmInput]) {
                          case RealmInput(name, openIdConfig, logo) =>
                            completeIO(StatusCodes.Created, realms.create(id, name, openIdConfig, logo))
                        }
                    }
                  },
                  get {
                    parameter("rev".as[Long].?) {
                      case Some(rev) =>
                        completeIOOpt(realms.fetchAt(id, rev).leftMap[RealmRejection] {
                          identity
                        })
                      case None      =>
                        completeUIOOpt(
                          realms.fetch(id)
                        )
                    }
                  },
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
  def apply(identities: Identities, realms: Realms)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): RealmsRoutes =
    new RealmsRoutes(identities, realms)

}
