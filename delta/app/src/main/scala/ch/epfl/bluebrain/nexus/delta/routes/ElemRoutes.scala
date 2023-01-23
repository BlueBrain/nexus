package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ElemRoutes.NotFound
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, lastEventId}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.baseUriPrefix
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tag}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

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
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck: AclCheck) {
  import baseUri.prefixSegment
  import schemeDirectives._

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        lastEventId { offset =>
          pathPrefix("elems") {
            resolveProjectRef { project =>
              authorizeFor(project, events.read).apply {
                concat(
                  (get & pathPrefix("continuous") & parameter("tag".as[UserTag].?)) { tag =>
                    operationName(s"$prefixSegment/$project/elems/continuous") {
                      emit(sseElemStream.continuous(project, tag.getOrElse(Tag.latest), offset))
                    }
                  },
                  (get & pathPrefix("currents") & parameter("tag".as[UserTag].?)) { tag =>
                    operationName(s"$prefixSegment/$project/elems/currents") {
                      emit(sseElemStream.currents(project, tag.getOrElse(Tag.latest), offset))
                    }
                  },
                  (get & pathPrefix("remaining") & parameter("tag".as[UserTag].?)) { tag =>
                    operationName(s"$prefixSegment/$project/elems/remaining") {
                      emit(
                        sseElemStream.remaining(project, tag.getOrElse(Tag.latest), offset).flatMap { r =>
                          IO.fromOption(r, NotFound(project, tag))
                        }
                      )
                    }
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
