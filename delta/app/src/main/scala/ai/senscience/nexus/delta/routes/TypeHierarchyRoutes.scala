package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.implicits.catsSyntaxApplicativeError
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.typehierarchy
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.TypeHierarchy as TypeHierarchyModel
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.{TypeHierarchy, TypeHierarchyRejection}

final class TypeHierarchyRoutes(
    typeHierarchy: TypeHierarchyModel,
    identities: Identities,
    aclCheck: AclCheck
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("type-hierarchy") {
          concat(
            // Fetch using the revision
            (get & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
              emit(typeHierarchy.fetch(rev).attemptNarrow[TypeHierarchyRejection])
            },
            // Fetch the type hierarchy
            (get & pathEndOrSingleSlash) {
              emit(typeHierarchy.fetch.attemptNarrow[TypeHierarchyRejection])
            },
            // Create the type hierarchy
            (post & pathEndOrSingleSlash) {
              entity(as[TypeHierarchy]) { payload =>
                authorizeFor(AclAddress.Root, typehierarchy.write).apply {
                  emit(StatusCodes.Created, typeHierarchy.create(payload.mapping).attemptNarrow[TypeHierarchyRejection])
                }
              }
            },
            // Update the type hierarchy
            (put & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
              entity(as[TypeHierarchy]) { payload =>
                authorizeFor(AclAddress.Root, typehierarchy.write).apply {
                  emit(typeHierarchy.update(payload.mapping, rev).attemptNarrow[TypeHierarchyRejection])
                }
              }
            }
          )
        }
      }
    }

}
