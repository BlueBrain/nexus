package ch.epfl.bluebrain.nexus.delta.plugins.jira.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Route}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.jira.model.{JiraResponse, Verifier}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{JiraClient, JiraError}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import io.circe.JsonObject
import io.circe.syntax.EncoderOps

/**
  * The Jira routes.
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param jiraClient
  *   the jira client
  */
class JiraRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    jiraClient: JiraClient
)(implicit baseUri: BaseUri, cr: RemoteContextResolution, ordering: JsonKeyOrdering)
    extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  private def extractUser: Directive1[User] = extractCaller.flatMap {
    _.subject match {
      case u: User => provide(u)
      case _       =>
        extractRequest.flatMap { request =>
          failWith(AuthorizationFailed(request))
        }
    }
  }

  private def adaptResponse(io: IO[JiraResponse]) =
    io.map(_.content).attemptNarrow[JiraError]

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("jira") {
        extractUser { implicit user =>
          concat(
            // Request token
            (pathPrefix("request-token") & post & pathEndOrSingleSlash) {
              emit(jiraClient.requestToken().map(_.asJson).attemptNarrow[RealmRejection])
            },
            // Get the access token
            (pathPrefix("access-token") & post & pathEndOrSingleSlash) {
              entity(as[Verifier]) { verifier =>
                emit(jiraClient.accessToken(verifier).attemptNarrow[RealmRejection])
              }
            },
            // Issues
            pathPrefix("issue") {
              concat(
                // Create an issue
                (post & entity(as[JsonObject])) { payload =>
                  emit(StatusCodes.Created, adaptResponse(jiraClient.createIssue(payload)))
                },
                // Edit an issue
                (put & pathPrefix(Segment)) { issueId =>
                  entity(as[JsonObject]) { payload =>
                    emit(StatusCodes.NoContent, adaptResponse(jiraClient.editIssue(issueId, payload)))
                  }
                },
                // Get an issue
                (get & pathPrefix(Segment)) { issueId =>
                  emit(adaptResponse(jiraClient.getIssue(issueId)))
                }
              )
            },
            // List projects
            (get & pathPrefix("project") & get & parameter("recent".as[Int].?)) { recent =>
              emit(adaptResponse(jiraClient.listProjects(recent)))
            },
            // Search issues
            (post & pathPrefix("search") & pathEndOrSingleSlash) {
              entity(as[JsonObject]) { payload =>
                emit(adaptResponse(jiraClient.search(payload)))
              }
            }
          )
        }
      }
    }
}
