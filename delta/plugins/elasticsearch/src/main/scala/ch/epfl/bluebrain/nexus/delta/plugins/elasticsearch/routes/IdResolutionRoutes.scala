package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{IdResolution, IdResolutionResponse}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import monix.execution.Scheduler

class IdResolutionRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    idResolution: IdResolution
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    jko: JsonKeyOrdering,
    rcr: RemoteContextResolution,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck) {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("resolve") {
        extractCaller { implicit caller =>
          (get & iriSegment & pathEndOrSingleSlash) { iri =>
            val resolved = idResolution.resolve(iri)

            val r          = resolved.mapError(_ => new Exception("Error")).hideErrors
            val fusionUris = r.flatMap {
              case _: IdResolutionResponse.Error                     =>
                fusionErrorUri
              case IdResolutionResponse.SingleResult(id, project, _) =>
                fusionResourceUri(project, id.iri)
              case IdResolutionResponse.MultipleResults(_)           =>
                fusionErrorUri
            }.hideErrors

            emitOrFusionRedirect(
              fusionUris,
              emit(resolved)
            )
          }
        }
      }
    }

}
