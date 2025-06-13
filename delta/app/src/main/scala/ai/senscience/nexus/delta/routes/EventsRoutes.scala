package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.{Directive1, Route}
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, lastEventId}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * The global events route.
  *
  * @param identities
  *   the identities operations bundle
  * @param aclCheck
  *   verify the acls for users
  * @param sseEventLog
  *   the event log
  */
class EventsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    sseEventLog: SseEventLog
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck: AclCheck) {

  private def resolveSelector: Directive1[Label] =
    label.flatMap { l =>
      if (sseEventLog.selectors.contains(l))
        provide(l)
      else
        reject()
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        lastEventId { offset =>
          concat(
            concat(
              // SSE for all events with a given selector
              (resolveSelector & pathPrefix("events") & pathEndOrSingleSlash & get) { selector =>
                concat(
                  authorizeFor(AclAddress.Root, events.read).apply {
                    emit(sseEventLog.streamBy(selector, offset))
                  },
                  (head & authorizeFor(AclAddress.Root, events.read)) {
                    complete(OK)
                  }
                )
              },
              // SSE for events with a given selector within a given organization
              (resolveSelector & label & pathPrefix("events") & pathEndOrSingleSlash & get) { (selector, org) =>
                concat(
                  authorizeFor(org, events.read).apply {
                    emit(sseEventLog.streamBy(selector, org, offset).attemptNarrow[OrganizationRejection])
                  },
                  (head & authorizeFor(org, events.read)) {
                    complete(OK)
                  }
                )
              },
              // SSE for events with a given selector within a given project
              (resolveSelector & projectRef & pathPrefix("events") & pathEndOrSingleSlash) { (selector, project) =>
                concat(
                  (get & authorizeFor(project, events.read)).apply {
                    emit(sseEventLog.streamBy(selector, project, offset).attemptNarrow[ProjectRejection])
                  },
                  (head & authorizeFor(project, events.read)) {
                    complete(OK)
                  }
                )
              }
            )
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
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      sseEventLog: SseEventLog
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new EventsRoutes(identities, aclCheck, sseEventLog).routes

}
