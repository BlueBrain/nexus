package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.baseUriPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.instances.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.{Latest, UserTag}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.RemainingElems
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

import java.time.Instant

/**
  * Route to stream elems as SSEs
  *
  * Note that this endpoint is experimental, susceptible to changes or removal
  */
class ElemRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    sseElemStream: SseElemStream,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck: AclCheck) {
  import schemeDirectives.*

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        lastEventId { offset =>
          pathPrefix("elems") {
            projectRef { project =>
              authorizeFor(project, events.read).apply {
                (parameter("tag".as[UserTag].?) & types(project)) { (tag, types) =>
                  concat(
                    (get & pathPrefix("continuous")) {
                      emit(
                        sseElemStream.continuous(project, SelectFilter(types, tag.getOrElse(Latest)), offset)
                      )
                    },
                    (get & pathPrefix("currents")) {
                      emit(sseElemStream.currents(project, SelectFilter(types, tag.getOrElse(Latest)), offset))
                    },
                    (get & pathPrefix("remaining")) {
                      emit(
                        sseElemStream
                          .remaining(project, SelectFilter(types, tag.getOrElse(Latest)), offset)
                          .map { r =>
                            r.getOrElse(RemainingElems(0L, Instant.EPOCH))
                          }
                      )
                    },
                    head {
                      complete(OK)
                    }
                  )
                }
              }
            }
          }
        }
      }
    }
}

object ElemRoutes {

  final private case class NotFound(project: ProjectRef, tag: Option[UserTag]) {
    def reason: String = s"'$project' or '$tag' are unknown to the system."
  }

  private object NotFound {

    implicit val notFoundEncoder: Encoder.AsObject[NotFound] = Encoder.AsObject.instance { n =>
      JsonObject("reason" -> n.reason.asJson)
    }

    implicit val notFoundJsonLdEncoder: JsonLdEncoder[NotFound] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

    implicit val responseFieldsNotFound: HttpResponseFields[NotFound] =
      HttpResponseFields(_ => StatusCodes.NotFound)
  }

}
