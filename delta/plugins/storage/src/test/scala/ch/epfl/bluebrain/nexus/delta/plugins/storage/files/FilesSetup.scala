package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{Storages, StoragesSetup, StoragesStatistics, StoragesStatisticsSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Organizations, Projects, ResourceIdCheck}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.execution.Scheduler

trait FilesSetup extends IOValues with RemoteContextResolutionFixture with ConfigFixtures with IOFixedClock {
  private val filesConfig = FilesConfig(aggregate, indexing)

  def init(
      org: Label,
      project: Project,
      storageTypeConfig: StorageTypeConfig
  )(implicit
      baseUri: BaseUri,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      subject: Subject,
      sc: Scheduler
  ): (Files, Storages) =
    AclSetup.init().map(init(org, project, _, storageTypeConfig)).accepted

  def init(
      org: Label,
      project: Project,
      acls: Acls,
      storageTypeConfig: StorageTypeConfig
  )(implicit
      baseUri: BaseUri,
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      subject: Subject,
      sc: Scheduler
  ): (Files, Storages) = {
    for {
      (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
    } yield init(orgs, projects, acls, storageTypeConfig)
  }.accepted

  def init(
      orgs: Organizations,
      projects: Projects,
      acls: Acls,
      storageTypeConfig: StorageTypeConfig
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): (Files, Storages) =
    init(orgs, projects, acls, StoragesStatisticsSetup.init(Map.empty), storageTypeConfig, allowedPerms: _*)

  def init(
      orgs: Organizations,
      projects: Projects,
      acls: Acls,
      storageStatistics: StoragesStatistics,
      storageTypeConfig: StorageTypeConfig,
      storagePermissions: Permission*
  )(implicit config: StorageTypeConfig, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): (Files, Storages) = {
    implicit val httpClient: HttpClient = HttpClient()(httpClientConfig, as.classicSystem, sc)
    for {
      storagesPerms <- PermissionsDummy(storagePermissions.toSet)
      storages       = StoragesSetup.init(orgs, projects, storagesPerms, storageTypeConfig)
      eventLog      <- EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors
      agg           <- Files.aggregate(filesConfig.aggregate, ResourceIdCheck.alwaysAvailable)
      files         <- Files(filesConfig, config, eventLog, acls, orgs, projects, storages, storageStatistics, agg)
    } yield files -> storages
  }.accepted
}

object FilesSetup extends FilesSetup
