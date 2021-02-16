package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.DiskStorageFields
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{MigrationState, ScopeInitialization}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the default disk storage as part of the project initialization. It performs a noop if
  * executed during a migration.
  *
  * @param storages       the storages module
  * @param serviceAccount the subject that will be recorded when performing the initialization
  */
class StorageScopeInitialization(storages: Storages, serviceAccount: ServiceAccount) extends ScopeInitialization {

  private val logger: Logger          = Logger[StorageScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  private val defaultStorageId: IdSegment     = IriSegment(nxv + "diskStorageDefault")
  private val defaultValue: DiskStorageFields = DiskStorageFields(
    default = true,
    volume = None,
    readPermission = None,
    writePermission = None,
    maxFileSize = None
  )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    if (MigrationState.isRunning) UIO.unit
    else
      storages
        .create(defaultStorageId, project.ref, defaultValue)
        .void
        .onErrorHandleWith {
          case _: StorageRejection.StorageAlreadyExists => UIO.unit // nothing to do, storage already exits
          case rej                                      =>
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
