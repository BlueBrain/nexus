package ch.epfl.bluebrain.nexus.delta.plugins.jira.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraClient
import ch.epfl.bluebrain.nexus.delta.plugins.jira.routes.JiraRoutes.Verifier
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{baseUriPrefix, emit}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, JsonObject}
import monix.execution.Scheduler

import scala.annotation.nowarn

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
              emit(jiraClient.requestToken().hideErrors.map(_.asJson))
            },
            (pathPrefix("access-token") & post & pathEndOrSingleSlash) {
              entity(as[Verifier]) { verifier =>
                emit(jiraClient.accessToken(verifier.value).hideErrors.map(_.asJson))
              }
            },
            (pathPrefix("search") & post & pathEndOrSingleSlash) {
              entity(as[JsonObject]) { payload =>
                emit(jiraClient.search(payload).hideErrors.map(_.asJson))
              }
            }
          )
        }
      }
    }
}

object JiraRoutes {

  final private[routes] case class Verifier(value: String)

  final private[routes] object Verifier {
    @nowarn("cat=unused")
    implicit final private val configuration: Configuration = Configuration.default.withStrictDecoding
    implicit val verificationCodeDecoder: Decoder[Verifier] = deriveConfiguredDecoder[Verifier]
  }
}
