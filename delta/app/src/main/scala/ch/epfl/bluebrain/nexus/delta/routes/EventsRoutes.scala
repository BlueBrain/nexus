package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, lastEventId}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, Projects, SseEventLog}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The global events route.
  *
  * @param identities
  *   the identities operations bundle
  * @param projects
  *   the projects operations bundle
  * @param aclCheck
  *   verify the acls for users
  * @param sseEventLog
  *   the event log
  */
class EventsRoutes(identities: Identities, aclCheck: AclCheck, projects: Projects, sseEventLog: SseEventLog)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck: AclCheck) {

  import baseUri.prefixSegment
  implicit private val fetchProjectUuids: FetchUuids = projects

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      (pathPrefix("events") & pathEndOrSingleSlash) {
        extractCaller { implicit caller =>
          concat(
            // SSE for all events
            get {
              operationName(s"$prefixSegment/events") {
                authorizeFor(AclAddress.Root, events.read).apply {
                  lastEventId { offset =>
                    emit(sseEventLog.stream(offset))
                  }
                }
              }
            },
            (head & authorizeFor(AclAddress.Root, events.read)) {
              complete(OK)
            }
          )
        }
      }
    }

}

object EventsRoutes {

  /**
    * @return
    *   [[Route]] for events.
    */
  def apply(identities: Identities, aclCheck: AclCheck, projects: Projects, sseEventLog: SseEventLog)(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new EventsRoutes(identities, aclCheck, projects, sseEventLog).routes

}
