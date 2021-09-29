package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.ownerPermissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotasConfig
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, ProjectsBehaviors}
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, Quotas}
import ch.epfl.bluebrain.nexus.delta.service.utils.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import monix.bio.Task
import org.scalatest.Inspectors

class ProjectsImplSpec extends AbstractDBSpec with ProjectsBehaviors with ConfigFixtures with Inspectors {

  val projectsConfig: ProjectsConfig =
    ProjectsConfig(
      aggregate,
      keyValueStore,
      pagination,
      cacheIndexing,
      persist,
      AutomaticProvisioningConfig.disabled,
      QuotasConfig(None, None, enabled = false, Map.empty),
      denyProjectPruning = false
    )

  override def create(quotas: Quotas): Task[Projects] =
    for {
      eventLog   <- EventLog.postgresEventLog[Envelope[ProjectEvent]](EventLogUtils.toEnvelope).hideErrors
      agg        <- ProjectsImpl.aggregate(
                      projectsConfig,
                      organizations,
                      ApiMappings.empty
                    )
      cache       = ProjectsImpl.cache(projectsConfig)
      deleteCache = ProjectsImpl.deletionCache(projectsConfig)
      projects   <-
        ProjectsImpl(
          agg,
          projectsConfig,
          eventLog,
          organizations,
          quotas,
          Set(new OwnerPermissionsScopeInitialization(acls, ownerPermissions, serviceAccount)),
          ApiMappings.empty,
          cache,
          deleteCache
        )
    } yield projects

}
