package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolution
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri

class IdResolutionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    idResolution: IdResolution
)(implicit
    baseUri: BaseUri,
    jko: JsonKeyOrdering,
    rcr: RemoteContextResolution,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck) {

  def routes: Route = concat(resolutionRoute, proxyRoute)

  private def resolutionRoute: Route =
    pathPrefix("resolve") {
      extractCaller { implicit caller =>
        (get & iriSegment & pathEndOrSingleSlash) { iri =>
          val resolved = idResolution.apply(iri)
          emit(resolved.attemptNarrow[ElasticSearchClientError])
        }
      }
    }

  private def proxyRoute: Route =
    pathPrefix("resolve-proxy-pass") {
      extractUnmatchedPath { path =>
        get {
          val htt4sPath  = org.http4s.Uri.unsafeFromString(path.toString())
          val resourceId = fusionConfig.resolveBase.resolve(htt4sPath)
          emitOrFusionRedirect(
            fusionResolveUri(resourceId),
            redirect(deltaResolveEndpoint(resourceId), StatusCodes.SeeOther)
          )
        }
      }
    }

  private def deltaResolveEndpoint(id: org.http4s.Uri): Uri =
    Uri((baseUri.endpoint / "resolve" / id.toString).toString())

}
