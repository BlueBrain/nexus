package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectFields, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Projects}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * Automatic project provisioning for users.
  */
trait ProjectProvisioning {

  /**
    * Provision a project for a user
    *
    * @param subject a user to provision a project for
    */
  def apply(subject: Subject): UIO[Unit]

}

object ProjectProvisioning {

  private val logger: Logger = Logger[ProjectProvisioning]

  /**
    * Create an instance of [[ProjectProvisioning]]
    * @param acls               ACLs operations
    * @param projects           project operations
    * @param provisioningConfig provisioning configuration
    */
  def apply(
      acls: Acls,
      projects: Projects,
      provisioningConfig: AutomaticProvisioningConfig
  ): ProjectProvisioning = new ProjectProvisioning {

    private def provisionOnNotFound(
        projectRef: ProjectRef,
        user: User,
        acls: Acls,
        provisioningConfig: AutomaticProvisioningConfig
    ): UIO[Unit] = {
      val acl = Acl(AclAddress.Project(projectRef), user -> provisioningConfig.permissions)
      (for {
        _ <- acls
               .append(acl, 0L)(user)
               .onErrorRecover { case _: AclRejection.IncorrectRev | _: AclRejection.NothingToBeUpdated => () }
               .mapError { rej => s"Failed to set ACL for user '$user' due to '${rej.reason}'." }
        _ <- projects
               .create(
                 projectRef,
                 ProjectFields(
                   Some(provisioningConfig.description),
                   provisioningConfig.apiMappings,
                   Some(provisioningConfig.base),
                   Some(provisioningConfig.vocab)
                 )
               )(user)
               .onErrorRecover { case _: ProjectAlreadyExists => () }
               .mapError { rej => s"Failed to provision project for '$user' due to '${rej.reason}'." }
      } yield ()).onErrorHandle { err =>
        logger.warn(err)
      }
    }

    override def apply(subject: Subject): UIO[Unit] = subject match {
      case user @ User(subject, realm) if provisioningConfig.enabled =>
        (for {
          org       <- IO.fromOption(provisioningConfig.enabledRealms.get(realm))
          proj      <- IO.fromEither(Label.apply(subject)).mapError { err =>
                         logger.warn(s"Failed to create a project label for $user, due to error '${err.getMessage}'")
                       }
          projectRef = ProjectRef(org, proj)
          exists    <- projects.projectExists(projectRef)
          _         <- IO.when(!exists)(provisionOnNotFound(projectRef, user, acls, provisioningConfig))
        } yield ()).onErrorHandle(_ => ())
      case _                                                         => UIO.unit
    }
  }
}
