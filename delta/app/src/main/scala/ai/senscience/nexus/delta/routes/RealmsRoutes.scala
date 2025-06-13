package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.{Directive1, Route}
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.realms as realmsPermissions
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{Realm, RealmFields, RealmRejection}

class RealmsRoutes(identities: Identities, realms: Realms, aclCheck: AclCheck)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  private def realmsSearchParams: Directive1[RealmSearchParams] =
    searchParams.tmap { case (deprecated, rev, createdBy, updatedBy) =>
      RealmSearchParams(None, deprecated, rev, createdBy, updatedBy)
    }

  private def emitFetch(io: IO[RealmResource]): Route = emit(io.attemptNarrow[RealmRejection])

  private def emitMetadata(statusCode: StatusCode, io: IO[RealmResource]): Route =
    emit(statusCode, io.mapValue(_.metadata).attemptNarrow[RealmRejection])

  private def emitMetadata(io: IO[RealmResource]): Route = emitMetadata(StatusCodes.OK, io)

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("realms") {
        extractCaller { implicit caller =>
          concat(
            // List realms
            (get & extractHttp4sUri & fromPaginated & realmsSearchParams & sort[Realm] & pathEndOrSingleSlash) {
              (uri, pagination, params, order) =>
                authorizeFor(AclAddress.Root, realmsPermissions.read).apply {
                  implicit val encoder: JsonLdEncoder[SearchResults[RealmResource]] =
                    searchResultsJsonLdEncoder(Realm.context, pagination, uri)
                  val result                                                        = realms
                    .list(pagination, params, order)
                    .widen[SearchResults[RealmResource]]
                  emit(result)
                }
            },
            (label & pathEndOrSingleSlash) { id =>
              concat(
                // Create or update a realm
                put {
                  authorizeFor(AclAddress.Root, realmsPermissions.write).apply {
                    parameter("rev".as[Int].?) {
                      case Some(rev) =>
                        // Update a realm
                        entity(as[RealmFields]) { fields => emitMetadata(realms.update(id, rev, fields)) }
                      case None      =>
                        // Create a realm
                        entity(as[RealmFields]) { fields =>
                          emitMetadata(StatusCodes.Created, realms.create(id, fields))
                        }
                    }
                  }
                },
                // Fetch a realm
                get {
                  authorizeFor(AclAddress.Root, realmsPermissions.read).apply {
                    parameter("rev".as[Int].?) {
                      case Some(rev) => // Fetch realm at specific revision
                        emitFetch(realms.fetchAt(id, rev))
                      case None      => // Fetch realm
                        emitFetch(realms.fetch(id))
                    }
                  }
                },
                // Deprecate realm
                delete {
                  authorizeFor(AclAddress.Root, realmsPermissions.write).apply {
                    parameter("rev".as[Int]) { rev =>
                      emitMetadata(realms.deprecate(id, rev))
                    }
                  }
                }
              )
            }
          )
        }
      }
    }
}

object RealmsRoutes {

  /**
    * @return
    *   the [[Route]] for realms
    */
  def apply(identities: Identities, realms: Realms, aclCheck: AclCheck)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new RealmsRoutes(identities, realms, aclCheck).routes

}
