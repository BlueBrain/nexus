package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileEvent, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{Storages, StoragesSetup, StoragesStatistics, StoragesStatisticsSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceIdCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, AclSimpleCheck}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.execution.Scheduler

trait FilesSetup extends IOValues with RemoteContextResolutionFixture with ConfigFixtures with IOFixedClock {
  private val filesConfig = FilesConfig(aggregate, cacheIndexing)

  def init(
      fetchContext: FetchContext[ContextRejection],
      storageTypeConfig: StorageTypeConfig
  )(implicit
      as: ActorSystem[Nothing],
      uuid: UUIDF,
      sc: Scheduler
  ): (Files, Storages) =
    AclSimpleCheck().map(init(fetchContext, _, storageTypeConfig)).accepted

  def init(
      fetchContext: FetchContext[ContextRejection],
      aclCheck: AclCheck,
      storageTypeConfig: StorageTypeConfig
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): (Files, Storages) =
    init(fetchContext, aclCheck, StoragesStatisticsSetup.init(Map.empty), storageTypeConfig, allowedPerms: _*)

  def init(
      fetchContext: FetchContext[ContextRejection],
      aclCheck: AclCheck,
      storageStatistics: StoragesStatistics,
      storageTypeConfig: StorageTypeConfig,
      storagePermissions: Permission*
  )(implicit config: StorageTypeConfig, as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): (Files, Storages) = {
    implicit val httpClient: HttpClient = HttpClient()(httpClientConfig, as.classicSystem, sc)
    for {
      eventLog <- EventLog.postgresEventLog[Envelope[FileEvent]](EventLogUtils.toEnvelope).hideErrors
      storages  = StoragesSetup.init(
                    fetchContext.mapRejection(StorageRejection.ProjectContextRejection),
                    storagePermissions.toSet,
                    storageTypeConfig
                  )
      agg      <- Files.aggregate(filesConfig.aggregate, ResourceIdCheck.alwaysAvailable)
      files    <- Files(
                    filesConfig,
                    config,
                    eventLog,
                    aclCheck,
                    fetchContext.mapRejection(FileRejection.ProjectContextRejection),
                    storages,
                    storageStatistics,
                    agg
                  )
    } yield files -> storages
  }.accepted
}

object FilesSetup extends FilesSetup
