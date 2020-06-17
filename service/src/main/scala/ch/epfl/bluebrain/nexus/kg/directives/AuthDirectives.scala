package ch.epfl.bluebrain.nexus.kg.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import akka.http.scaladsl.server.{Directive0, Directive1}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.iam.client.{IamClient, IamClientError}
import ch.epfl.bluebrain.nexus.kg.KgError.{AuthenticationFailed, AuthorizationFailed, InternalError}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.util.{Failure, Success}

object AuthDirectives {

  private val logger = Logger[this.type]

  /**
    * Extracts the credentials from the HTTP Authorization Header and builds the [[AuthToken]]
    */
  def extractToken: Directive1[Option[AuthToken]] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(value)) => provide(Some(AuthToken(value)))
      case Some(_)                        => failWith(AuthenticationFailed)
      case _                              => provide(None)
    }

  /**
    * Checks if the current caller has the required permission.
    *
    * @param perm the permission to check on the current project
    * @return pass if the ''perm'' is present on the current project, fail with [[AuthorizationFailed]] otherwise
    */
  def hasPermission(perm: Permission)(implicit acls: AccessControlLists, caller: Caller, project: Project): Directive0 =
    if (acls.exists(caller.identities, project.projectLabel, perm)) pass
    else failWith(AuthorizationFailed)

  /**
    * Checks if the current caller has the required permission.
    *
    * @param perm     the permission to check on the current organization
    * @param orgLabel the organization label
    * @return pass if the ''perm'' is present on the current project, fail with [[AuthorizationFailed]] otherwise
    */
  def hasPermission(perm: Permission, orgLabel: String)(implicit acls: AccessControlLists, caller: Caller): Directive0 =
    if (acls.exists(caller.identities, orgLabel, perm)) pass
    else failWith(AuthorizationFailed)

  /**
    * Checks if the current caller has the required permissions on `/`
    *
    * @param  perms the permissions to check on `/`
    * @return pass if the ''perms'' are present on `/`, fail with [[AuthorizationFailed]] otherwise
    */
  def hasPermissionOnRoot(perms: Permission)(implicit
      acls: AccessControlLists,
      caller: Caller
  ): Directive0 =
    if (acls.existsOnRoot(caller.identities, perms)) pass
    else failWith(AuthorizationFailed)

  /**
    * Retrieves the caller ACLs.
    */
  def extractCallerAcls(implicit iam: IamClient[Task], token: Option[AuthToken]): Directive1[AccessControlLists] = {
    import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
    onComplete(iam.acls("*" / "*", ancestors = true, self = true).runToFuture).flatMap {
      case Success(result)                         => provide(result)
      case Failure(_: IamClientError.Unauthorized) => failWith(AuthenticationFailed)
      case Failure(_: IamClientError.Forbidden)    => failWith(AuthorizationFailed)
      case Failure(err)                            =>
        val message = "Error when trying to check for permissions"
        logger.error(message, err)
        failWith(InternalError(message))
    }
  }

  /**
    * Authenticates the requested with the provided ''token'' and returns the ''caller''
    */
  def extractCaller(implicit iam: IamClient[Task], token: Option[AuthToken]): Directive1[Caller] =
    onComplete(iam.identities.runToFuture).flatMap {
      case Success(caller)                         => provide(caller)
      case Failure(_: IamClientError.Unauthorized) => failWith(AuthenticationFailed)
      case Failure(_: IamClientError.Forbidden)    => failWith(AuthorizationFailed)
      case Failure(err)                            =>
        val message = "Error when trying to extract the subject"
        logger.error(message, err)
        failWith(InternalError(message))
    }
}
