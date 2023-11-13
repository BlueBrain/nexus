package ch.epfl.bluebrain.nexus.delta.plugins.storage

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.DiskStorageFields
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.{ProjectContextRejection, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{defaultStorageId, Storages}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity

/**
  * The default creation of the default disk storage as part of the project initialization.
  *
  * @param storages
  *   the storages module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class StorageScopeInitialization(
    storages: Storages,
    serviceAccount: ServiceAccount,
    defaults: Defaults
) extends ScopeInitialization {

  private val logger                                        = Logger[StorageScopeInitialization]
  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  implicit private val caller: Caller = serviceAccount.caller

  private val defaultValue: DiskStorageFields = DiskStorageFields(
    name = Some(defaults.name),
    description = Some(defaults.description),
    default = true,
    volume = None,
    readPermission = None,
    writePermission = None,
    capacity = None,
    maxFileSize = None
  )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[Unit] =
    storages
      .create(defaultStorageId, project.ref, defaultValue)
      .void
      .handleErrorWith {
        case _: ResourceAlreadyExists   => IO.unit // nothing to do, storage already exits
        case _: ProjectContextRejection => IO.unit // project or org are likely deprecated
        case rej                        =>
          val str =
            s"Failed to create the default DiskStorage for project '${project.ref}' due to '${rej.getMessage}'."
          logger.error(str) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .span("createDefaultStorage")

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[Unit] = IO.unit

}
