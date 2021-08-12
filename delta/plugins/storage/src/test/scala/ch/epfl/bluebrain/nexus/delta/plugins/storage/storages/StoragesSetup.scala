package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{ConfigFixtures, RemoteContextResolutionFixture}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{PermissionsDummy, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Permissions, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.IO
import monix.execution.Scheduler

trait StoragesSetup extends IOValues with RemoteContextResolutionFixture with ConfigFixtures with IOFixedClock {

  val serviceAccount: ServiceAccount = ServiceAccount(User("nexus-sa", Label.unsafe("sa")))

  def init(
      org: Label,
      project: Project,
      perms: Permission*
  )(implicit baseUri: BaseUri, as: ActorSystem[Nothing], uuid: UUIDF, subject: Subject, sc: Scheduler): Storages = {
    for {
      (orgs, projects) <- ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil)
      p                <- PermissionsDummy(perms.toSet)
    } yield init(orgs, projects, p)
  }.accepted

  def init(
      orgs: Organizations,
      projects: Projects,
      perms: Permissions
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): Storages =
    init(orgs, projects, perms, config)

  def init(
      orgs: Organizations,
      projects: Projects,
      perms: Permissions,
      storageTypeConfig: StorageTypeConfig
  )(implicit as: ActorSystem[Nothing], uuid: UUIDF, sc: Scheduler): Storages = {
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
      resolverCtx = new ResolverContextResolution(rcr, (_, _, _) => IO.raiseError(ResourceResolutionReport()))
      config      = StoragesConfig(aggregate, keyValueStore, pagination, indexing, persist, storageTypeConfig)
      storages   <-
        Storages(
          config,
          eventLog,
          resolverCtx,
          perms,
          orgs,
          projects,
          (_, _) => IO.unit,
          (_, _) => IO.unit,
          crypto,
          serviceAccount
        )
    } yield storages
  }.accepted
}

object StoragesSetup extends StoragesSetup
