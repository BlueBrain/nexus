package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
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
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

class IdResolutionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    idResolution: IdResolution
)(implicit s: Scheduler, jko: JsonKeyOrdering, rcr: RemoteContextResolution, fusionConfig: FusionConfig)
    extends AuthDirectives(identities, aclCheck) {

  def routes: Route =
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

  private def fusionUri(
      resolved: IO[ElasticSearchQueryError, IdResolutionResponse.Result]
  ): UIO[Uri] =
    resolved
      .flatMap {
        case SingleResult(id, project, _) => fusionResourceUri(project, id.iri)
        case MultipleResults(_)           => fusionLoginUri
      }
      .onErrorHandleWith { _ => fusionLoginUri }

}
