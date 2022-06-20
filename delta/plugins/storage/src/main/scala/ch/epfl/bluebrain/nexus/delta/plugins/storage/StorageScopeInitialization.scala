package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.DiskStorageFields
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{ResourceAlreadyExists, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{defaultStorageId, Storages}
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the default disk storage as part of the project initialization.
  *
  * @param storages
  *   the storages module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class StorageScopeInitialization(storages: Storages, serviceAccount: ServiceAccount) extends ScopeInitialization {

  private val logger: Logger          = Logger[StorageScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  private val defaultValue: DiskStorageFields = DiskStorageFields(
    default = true,
    volume = None,
    readPermission = None,
    writePermission = None,
    capacity = None,
    maxFileSize = None
  )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    storages
      .create(defaultStorageId, project.ref, defaultValue)
      .void
      .onErrorHandleWith {
        case _: ResourceAlreadyExists        => UIO.unit // nothing to do, storage already exits
        case _: WrappedProjectRejection      => UIO.unit // project is likely deprecated
        case _: WrappedOrganizationRejection => UIO.unit // org is likely deprecated
        case rej                             =>
          val str =
            s"Failed to create the default DiskStorage for project '${project.ref}' due to '${rej.reason}'."
          UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .named("createDefaultStorage", Storages.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit

}
