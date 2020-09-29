package ch.epfl.bluebrain.nexus.delta

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection

/**
  * Typeclass definition for ''A''s that can be mapped into a StatusCode.
  *
 * @tparam A generic type parameter
  */
trait StatusFrom[A] {

  /**
    * Computes a [[StatusCode]] instance from the argument value.
    *
   * @param value the input value
    * @return the status code corresponding to the value
    */
  def apply(value: A): StatusCode
}

// $COVERAGE-OFF$
object StatusFrom {

  /**
    * Lifts a function ''A => StatusCode'' into a ''StatusFrom[A]'' instance.
    *
    * @param f function from A to StatusCode
    * @tparam A type parameter to map to StatusCode
    * @return a ''StatusFrom'' instance from the argument function
    */
  def apply[A](f: A => StatusCode): StatusFrom[A] = (value: A) => f(value)

  implicit val statusFromPermissions: StatusFrom[PermissionsRejection] = StatusFrom {
    case PermissionsRejection.IncorrectRev(_, _)     => StatusCodes.Conflict
    case PermissionsRejection.RevisionNotFound(_, _) => StatusCodes.NotFound
    case _                                           => StatusCodes.BadRequest
  }

  implicit val statusFromAcls: StatusFrom[AclRejection] = StatusFrom {
    case AclRejection.AclNotFound(_)            => StatusCodes.NotFound
    case AclRejection.IncorrectRev(_, _, _)     => StatusCodes.Conflict
    case AclRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
    case AclRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
    case _                                      => StatusCodes.BadRequest
  }

  implicit val statusFromIdentities: StatusFrom[TokenRejection] = StatusFrom { _ =>
    StatusCodes.Unauthorized
  }

  implicit val statusFromRealms: StatusFrom[RealmRejection] = StatusFrom {
    case RealmRejection.RevisionNotFound(_, _)    => StatusCodes.NotFound
    case RealmRejection.RealmNotFound(_)          => StatusCodes.NotFound
    case RealmRejection.IncorrectRev(_, _)        => StatusCodes.Conflict
    case RealmRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
    case _                                        => StatusCodes.BadRequest
  }

  implicit val statusFromOrganizations: StatusFrom[OrganizationRejection] = StatusFrom {
    case OrganizationRejection.OrganizationNotFound(_) => StatusCodes.NotFound
    case OrganizationRejection.IncorrectRev(_, _)      => StatusCodes.Conflict
    case OrganizationRejection.RevisionNotFound(_, _)  => StatusCodes.NotFound
    //case OrganizationRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
    case _                                             => StatusCodes.BadRequest
  }

  implicit val statusFromProjects: StatusFrom[ProjectRejection] = StatusFrom {
    case ProjectRejection.RevisionNotFound(_, _)  => StatusCodes.NotFound
    case ProjectRejection.ProjectNotFound(_)      => StatusCodes.NotFound
    case ProjectRejection.OrganizationNotFound(_) => StatusCodes.NotFound
    case ProjectRejection.IncorrectRev(_, _)      => StatusCodes.Conflict
    //case ProjectRejection.UnexpectedInitialState(_) => StatusCodes.InternalServerError
    case _                                        => StatusCodes.BadRequest
  }
}
// $COVERAGE-ON$
