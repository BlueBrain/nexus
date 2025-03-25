package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.{BlazegraphViewByNamespace, SparqlSupervision}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.supervision
import io.circe.syntax.EncoderOps

class BlazegraphSupervisionRoutes(
    blazegraphSupervision: SparqlSupervision,
    identities: Identities,
    aclCheck: AclCheck
)(implicit cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with RdfMarshalling {

  def routes: Route =
    pathPrefix("supervision") {
      extractCaller { implicit caller =>
        authorizeFor(AclAddress.Root, supervision.read).apply {
          (pathPrefix("blazegraph") & get & pathEndOrSingleSlash) {
            emit(blazegraphSupervision.get.map(_.asJson))
          }
        }
      }
    }
}

object BlazegraphSupervisionRoutes {

  def apply(views: BlazegraphViews, client: SparqlClient, identities: Identities, aclCheck: AclCheck)(implicit
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): BlazegraphSupervisionRoutes = {
    val viewsByNameSpace      = BlazegraphViewByNamespace(views)
    val blazegraphSupervision = SparqlSupervision(client, viewsByNameSpace)
    new BlazegraphSupervisionRoutes(blazegraphSupervision, identities, aclCheck)
  }

}
