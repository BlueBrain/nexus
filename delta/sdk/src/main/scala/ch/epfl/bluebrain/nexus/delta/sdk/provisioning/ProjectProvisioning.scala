package ch.epfl.bluebrain.nexus.delta.sdk.provisioning

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress, AclRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

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
  def apply(subject: Subject): IO[Unit]

}

object ProjectProvisioning {

  private val logger = Logger[ProjectProvisioning]

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
    * @param appendAcls
    *   how to append acls
    * @param projects
    *   project operations
    * @param provisioningConfig
    *   provisioning configuration
    */
  def apply(
      appendAcls: Acl => IO[Unit],
      projects: Projects,
      provisioningConfig: AutomaticProvisioningConfig
  ): ProjectProvisioning = new ProjectProvisioning {

    private def provisionOnNotFound(
        projectRef: ProjectRef,
        user: User,
        provisioningConfig: AutomaticProvisioningConfig
    ): IO[Unit] = {
      val acl = Acl(AclAddress.Project(projectRef), user -> provisioningConfig.permissions)
      for {
        _ <- logger.info(s"Starting provisioning project for user ${user.subject}")
        _ <- appendAcls(acl)
               .recoverWith {
                 case _: AclRejection.IncorrectRev       => IO.unit
                 case _: AclRejection.NothingToBeUpdated => IO.unit
               }
               .adaptError { case r: AclRejection => UnableToSetAcls(r) }
        _ <- projects
               .create(
                 projectRef,
                 provisioningConfig.fields
               )(user)
               .void
               .recoverWith { case _: ProjectAlreadyExists => IO.unit }
               .adaptError { case r: ProjectRejection => UnableToCreateProject(r) }
        _ <- logger.info(s"Provisioning project for user ${user.subject} succeeded.")
      } yield ()
    }

    override def apply(subject: Subject): IO[Unit] = subject match {
      case user @ User(subject, realm) if provisioningConfig.enabled =>
        provisioningConfig.enabledRealms.get(realm) match {
          case Some(org) =>
            for {
              proj      <-
                IO.fromEither(Label.sanitized(subject)).adaptError { case e: FormatError => InvalidProjectLabel(e) }
              projectRef = ProjectRef(org, proj)
              exists    <- projects.fetch(projectRef).as(true).handleError(_ => false)
              _         <- IO.whenA(!exists)(provisionOnNotFound(projectRef, user, provisioningConfig))
            } yield ()
          case None      => IO.unit
        }
      case _                                                         => IO.unit
    }
  }

  /**
    * Create an instance of [[ProjectProvisioning]] from an [[Acls]] instance
    * @param acls
    *   an acls instance
    * @param projects
    *   project operations
    * @param provisioningConfig
    *   provisioning configuration
    */
  def apply(
      acls: Acls,
      projects: Projects,
      provisioningConfig: AutomaticProvisioningConfig,
      serviceAccount: ServiceAccount
  ): ProjectProvisioning = {
    implicit val serviceAccountSubject: Subject = serviceAccount.subject
    apply(acls.append(_, 0).void, projects, provisioningConfig)
  }
}
