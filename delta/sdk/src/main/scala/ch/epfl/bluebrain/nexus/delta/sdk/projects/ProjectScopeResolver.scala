package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait ProjectScopeResolver {

  def apply(scope: Scope, permission: Permission)(implicit caller: Caller): IO[Set[ProjectRef]]

}

object ProjectScopeResolver {
  def apply(projects: Projects, aclCheck: AclCheck): ProjectScopeResolver =
    apply(projects.currentRefs(_).compile.toList, aclCheck)

  def apply(fetchProjects: Scope => IO[List[ProjectRef]], aclCheck: AclCheck): ProjectScopeResolver =
    new ProjectScopeResolver {
      override def apply(scope: Scope, permission: Permission)(implicit caller: Caller): IO[Set[ProjectRef]] = {
        for {
          projects           <- fetchProjects(scope)
          authorizedProjects <-
            aclCheck.mapFilter[ProjectRef, ProjectRef](projects, ProjectAcl(_) -> permission, identity)(caller)
          _                  <- IO.raiseWhen(authorizedProjects.isEmpty)(AuthorizationFailed("No projects are accessible."))
        } yield authorizedProjects
      }
    }

}
