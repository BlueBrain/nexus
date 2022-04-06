package ch.epfl.bluebrain.nexus.delta.plugins.jira.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraClient
import ch.epfl.bluebrain.nexus.delta.plugins.jira.model.Verifier
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{baseUriPrefix, emit}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import monix.execution.Scheduler

/**
  * The Jira routes.
  *
  * @param identities
  *   the identity module
  * @param acls
  *   the acls module
  * @param jiraClient
  *   the jira client
  */
class JiraRoutes(
    identities: Identities,
    acls: Acls,
    jiraClient: JiraClient
)(implicit baseUri: BaseUri, s: Scheduler, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with RdfMarshalling {

  private def extractUser: Directive1[User] = extractCaller.flatMap {
    _.subject match {
      case u: User => provide(u)
      case _       => failWith(AuthorizationFailed)
    }
  }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("jira") {
        extractUser { implicit user =>
          concat(
            // Request token
            (pathPrefix("request-token") & post & pathEndOrSingleSlash) {
              emit(jiraClient.requestToken().map(_.asJson))
            },
            // Get the access token
            (pathPrefix("access-token") & post & pathEndOrSingleSlash) {
              entity(as[Verifier]) { verifier =>
                emit(jiraClient.accessToken(verifier).map(_.asJson))
              }
            },
            // Search issues
            (pathPrefix("search") & post & pathEndOrSingleSlash) {
              entity(as[JsonObject]) { payload =>
                emit(jiraClient.search(payload).map(_.asJson))
              }
            }
          )
        }
      }
    }
}
