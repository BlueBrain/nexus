package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.IdResolutionResponse.{MultipleResults, SingleResult}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{IdResolution, IdResolutionResponse}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

class IdResolutionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    idResolution: IdResolution,
    baseUri: BaseUri
)(implicit s: Scheduler, jko: JsonKeyOrdering, rcr: RemoteContextResolution, fusionConfig: FusionConfig)
    extends AuthDirectives(identities, aclCheck) {

  def routes: Route = concat(resolutionRoute, proxyRoute)

  def resolutionRoute: Route =
    pathPrefix("resolve") {
      extractCaller { implicit caller =>
        (get & iriSegment & pathEndOrSingleSlash) { iri =>
          val resolved = idResolution.resolve(iri)

          emitOrFusionRedirect(
            fusionUri(resolved),
            emit(resolved)
          )
        }
      }
    }

  def proxyRoute: Route = {
    pathPrefix("resolve-proxy-pass") {
      pathPrefix(Segment) { segment =>
        get {
          val resourceId = neurosciencegraph(segment)
          emitOrFusionRedirect(
            fusionResolveUri(resourceId),
            redirect(deltaResolveEndpoint(resourceId), StatusCodes.SeeOther)
          )
        }
      }
    }
  }

  private def fusionUri(
      resolved: IO[ElasticSearchQueryError, IdResolutionResponse.Result]
  ): UIO[Uri] =
    resolved
      .flatMap {
        case SingleResult(id, project, _) => fusionResourceUri(project, id.iri)
        case MultipleResults(_)           => fusionLoginUri
      }
      .onErrorHandleWith { _ => fusionLoginUri }

  private def deltaResolveEndpoint(id: String) =
    s"$baseUri/resolve/${UrlUtils.encode(id)}"

  private def neurosciencegraph(segment: String) =
    s"https://bbp.epfl.ch/neurosciencegraph/data/$segment"

}
