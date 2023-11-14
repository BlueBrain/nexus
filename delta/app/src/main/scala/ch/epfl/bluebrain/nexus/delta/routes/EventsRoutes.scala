package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.{Directive1, Route}
import cats.effect.unsafe.IORuntime
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, lastEventId}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import kamon.instrumentation.akka.http.TracingDirectives.operationName

/**
  * The global events route.
  *
  * @param identities
  *   the identities operations bundle
  * @param aclCheck
  *   verify the acls for users
  * @param sseEventLog
  *   the event log
  * @param schemeDirectives
  *   directives related to orgs and projects
  */
class EventsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    sseEventLog: SseEventLog,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    runtime: IORuntime
) extends AuthDirectives(identities, aclCheck: AclCheck) {

  import baseUri.prefixSegment
  import schemeDirectives._

  private def resolveSelector: Directive1[Label] =
    label.flatMap { l =>
      if (sseEventLog.allSelectors.contains(l))
        provide(l)
      else
        reject()
    }

  private def resolveScopedSelector: Directive1[Label] = label.flatMap { l =>
    if (sseEventLog.scopedSelectors.contains(l))
      provide(l)
    else
      reject()
  }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        lastEventId { offset =>
          concat(
            (pathPrefix("events") & pathEndOrSingleSlash) {
              concat(
                // SSE for all events
                get {
                  operationName(s"$prefixSegment/events") {
                    authorizeFor(AclAddress.Root, events.read).apply {
                      emit(sseEventLog.stream(offset))
                    }
                  }
                },
                (head & authorizeFor(AclAddress.Root, events.read)) {
                  complete(OK)
                }
              )
            },
            get {
              concat(
                // SSE for all events with a given selector
                (resolveSelector & pathPrefix("events") & pathEndOrSingleSlash) { selector =>
                  operationName(s"$prefixSegment/$selector/{org}/events") {
                    concat(
                      authorizeFor(AclAddress.Root, events.read).apply {
                        emit(sseEventLog.streamBy(selector, offset))
                      },
                      (head & authorizeFor(AclAddress.Root, events.read)) {
                        complete(OK)
                      }
                    )
                  }
                },
                // SSE for events with a given selector within a given organization
                (resolveScopedSelector & resolveOrg & pathPrefix("events") & pathEndOrSingleSlash) { (selector, org) =>
                  operationName(s"$prefixSegment/$selector/{org}/events") {
                    concat(
                      authorizeFor(org, events.read).apply {
                        emit(sseEventLog.streamBy(selector, org, offset).attemptNarrow[OrganizationRejection])
                      },
                      (head & authorizeFor(org, events.read)) {
                        complete(OK)
                      }
                    )
                  }
                },
                // SSE for events with a given selector within a given project
                (resolveScopedSelector & resolveProjectRef & pathPrefix("events") & pathEndOrSingleSlash) {
                  (selector, projectRef) =>
                    concat(
                      operationName(s"$prefixSegment/$selector/{org}/{proj}/events") {
                        authorizeFor(projectRef, events.read).apply {
                          emit(sseEventLog.streamBy(selector, projectRef, offset).attemptNarrow[ProjectRejection])
                        }
                      },
                      (head & authorizeFor(projectRef, events.read)) {
                        complete(OK)
                      }
                    )
                }
              )
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
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      sseEventLog: SseEventLog,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      runtime: IORuntime
  ): Route = new EventsRoutes(identities, aclCheck, sseEventLog, schemeDirectives).routes

}
