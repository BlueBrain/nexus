package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.error.{FormatError, SDKError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Projects}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectProvisioning.ProjectProvisioningRejection
import monix.bio.{IO, UIO}

/**
  * Automatic project provisioning for users.
  */
trait ProjectProvisioning {

  /**
    * Provision a project for a user
    *
    * @param subject
    *   a user to provision a project for
    */
  def apply(subject: Subject): IO[ProjectProvisioningRejection, Unit]

}

object ProjectProvisioning {

  /**
    * Rejection signalling that project provisioning failed.
    */
  abstract class ProjectProvisioningRejection(reason: String) extends SDKError {
    override def getMessage: String = reason
  }

  /**
    * Rejection signalling that we were not able to crate project label from username.
    */
  final case class InvalidProjectLabel(err: FormatError) extends ProjectProvisioningRejection(err.getMessage)

  /**
    * Rejection signalling that we were unable to set ACLs for user.
    */
  final case class UnableToSetAcls(err: AclRejection) extends ProjectProvisioningRejection(err.reason)

  /**
    * Rejection signalling that we were unable to create the project for user.
    */
  final case class UnableToCreateProject(err: ProjectRejection) extends ProjectProvisioningRejection(err.reason)

  /**
    * Create an instance of [[ProjectProvisioning]]
    * @param acls
    *   ACLs operations
    * @param projects
    *   project operations
    * @param provisioningConfig
    *   provisioning configuration
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
    ): IO[ProjectProvisioningRejection, Unit] = {
      val acl = Acl(AclAddress.Project(projectRef), user -> provisioningConfig.permissions)
      for {
        _ <- acls
               .append(acl, 0L)(user)
               .onErrorRecover { case _: AclRejection.IncorrectRev | _: AclRejection.NothingToBeUpdated => () }
               .mapError(UnableToSetAcls)
        _ <- projects
               .create(
                 projectRef,
                 provisioningConfig.fields
               )(user)
               .onErrorRecover { case _: ProjectAlreadyExists => () }
               .mapError(UnableToCreateProject)
      } yield ()
    }

    override def apply(subject: Subject): IO[ProjectProvisioningRejection, Unit] = subject match {
      case user @ User(subject, realm) if provisioningConfig.enabled =>
        provisioningConfig.enabledRealms.get(realm) match {
          case Some(org) =>
            for {
              proj      <- IO.fromEither(Label.sanitized(subject)).mapError(InvalidProjectLabel)
              projectRef = ProjectRef(org, proj)
              exists    <- projects.fetch(projectRef).map(_ => true).onErrorHandle(_ => false)
              _         <- IO.when(!exists)(provisionOnNotFound(projectRef, user, acls, provisioningConfig))
            } yield ()
          case None      => IO.unit
        }
      case _                                                         => UIO.unit
    }
  }
}
