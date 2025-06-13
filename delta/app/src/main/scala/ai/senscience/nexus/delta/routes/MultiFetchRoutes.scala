package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.baseUriPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.{jsonCodecDropNull, jsonSourceCodec}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceRepresentation}
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.MultiFetch
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.circe.Json

/**
  * Route allowing to fetch multiple resources in a single request
  */
class MultiFetchRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    multiFetch: MultiFetch
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("multi-fetch") {
        pathPrefix("resources") {
          extractCaller { implicit caller =>
            ((get | post) & entity(as[MultiFetchRequest])) { request =>
              implicit val codec: JsonValueCodec[Json] = selectCodec(request)
              emit(multiFetch(request).flatMap(_.asJson))
            }
          }
        }
      }
    }

  private def selectCodec(request: MultiFetchRequest) =
    if (
      request.format == ResourceRepresentation.SourceJson ||
      request.format == ResourceRepresentation.AnnotatedSourceJson
    )
      jsonSourceCodec
    else
      jsonCodecDropNull

}
