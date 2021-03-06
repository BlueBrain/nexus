package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{Storages, StoragesSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, IndexingActionDummy, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, IndexingAction, Organizations, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler

trait FilesSetup extends IOValues with RemoteContextResolutionFixture with ConfigFixtures with IOFixedClock {
  private val filesConfig = FilesConfig(aggregate, indexing)

  def init(
      org: Label,
      project: Project,
      storageTypeConfig: StorageTypeConfig,
      indexingAction: IndexingAction
  )(implicit
      baseUri: BaseUri,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      subject: Subject,
      sc: Scheduler
  ): (Files, Storages) =
    AclSetup.init().map(init(org, project, _, storageTypeConfig, indexingAction)).accepted

  def init(
      org: Label,
      project: Project,
      acls: Acls,
      storageTypeConfig: StorageTypeConfig,
      indexingAction: IndexingAction
  )(implicit
      baseUri: BaseUri,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      subject: Subject,
      sc: Scheduler
  ): (Files, Storages) = {
    for {
      (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    } yield init(orgs, projects, acls, storageTypeConfig, indexingAction)
  }.accepted

  def init(
      orgs: Organizations,
      projects: Projects,
      acls: Acls,
      storageTypeConfig: StorageTypeConfig,
      indexingAction: IndexingAction
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): (Files, Storages) =
    init(orgs, projects, acls, storageTypeConfig, indexingAction, allowedPerms: _*)

  def init(
      orgs: Organizations,
      projects: Projects,
      acls: Acls,
      storageTypeConfig: StorageTypeConfig,
      indexingAction: IndexingAction,
      storagePermissions: Permission*
  )(implicit config: StorageTypeConfig, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): (Files, Storages) = {
    implicit val httpClient: HttpClient = HttpClient()(httpClientConfig, as.classicSystem, sc)
    for {
      storagesPerms <- PermissionsDummy(storagePermissions.toSet)
      storages       = StoragesSetup.init(orgs, projects, storagesPerms, storageTypeConfig, IndexingActionDummy())
      eventLog      <- EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors
      files         <- Files(filesConfig, config, eventLog, acls, orgs, projects, storages, (_, _) => IO.unit, indexingAction)
    } yield files -> storages
  }.accepted
}

object FilesSetup extends FilesSetup
