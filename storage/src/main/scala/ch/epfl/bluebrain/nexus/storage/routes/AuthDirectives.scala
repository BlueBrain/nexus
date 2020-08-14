package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.{AccessToken, Caller}
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClientError.IdentitiesClientStatusError
import ch.epfl.bluebrain.nexus.storage.StorageError._
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.util.{Failure, Success}

object AuthDirectives {

  private val logger = Logger[this.type]

  /**
    * Extracts the credentials from the HTTP Authorization Header and builds the [[AccessToken]]
    */
  def extractToken: Directive1[Option[AccessToken]] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(value)) => provide(Some(AccessToken(value)))
      case Some(_)                        => failWith(AuthenticationFailed)
      case _                              => provide(None)
    }

  /**
    * Authenticates the requested with the provided ''token'' and returns the ''caller''
    */
  def extractCaller(implicit identities: DeltaIdentitiesClient[Task], token: Option[AccessToken]): Directive1[Caller] =
    onComplete(identities().runToFuture).flatMap {
      case Success(caller)                                                   => provide(caller)
      case Failure(IdentitiesClientStatusError(StatusCodes.Unauthorized, _)) => failWith(AuthenticationFailed)
      case Failure(IdentitiesClientStatusError(StatusCodes.Forbidden, _))    => failWith(AuthorizationFailed)
      case Failure(err)                                                      =>
        val message = "Error when trying to extract the subject"
        logger.error(message, err)
        failWith(InternalError(message))
    }
}
