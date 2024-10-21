package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.BlazegraphSupervision
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.supervision.CompositeViewsByNamespace
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

class CompositeSupervisionRoutes(
    blazegraphSupervision: BlazegraphSupervision,
    identities: Identities,
    aclCheck: AclCheck
)(implicit cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with RdfMarshalling {

  def routes: Route =
    pathPrefix("supervision") {
      extractCaller { implicit caller =>
        authorizeFor(AclAddress.Root, supervision.read).apply {
          (pathPrefix("composite-views") & get & pathEndOrSingleSlash) {
            emit(blazegraphSupervision.get.map(_.asJson))
          }
        }
      }
    }
}

object CompositeSupervisionRoutes {
  def apply(
      views: CompositeViews,
      client: BlazegraphClient,
      identities: Identities,
      aclCheck: AclCheck,
      prefix: String
  )(implicit cr: RemoteContextResolution, ordering: JsonKeyOrdering): CompositeSupervisionRoutes = {
    val viewsByNameSpace     = CompositeViewsByNamespace(views, prefix)
    val compositeSupervision = BlazegraphSupervision(client, viewsByNameSpace)
    new CompositeSupervisionRoutes(compositeSupervision, identities, aclCheck)
  }
}
