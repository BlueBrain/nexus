package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.ownerPermissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, ProjectsBehaviors}
import ch.epfl.bluebrain.nexus.delta.service.utils.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import monix.bio.Task

class ProjectsImplSpec extends AbstractDBSpec with ProjectsBehaviors with ConfigFixtures {

  val projectsConfig: ProjectsConfig =
    ProjectsConfig(
      aggregate,
      keyValueStore,
      pagination,
      cacheIndexing,
      persist,
      AutomaticProvisioningConfig.disabled
    )

  override def create: Task[Projects] =
    for {
      eventLog <- EventLog.postgresEventLog[Envelope[ProjectEvent]](EventLogUtils.toEnvelope).hideErrors
      projects <-
        ProjectsImpl(
          projectsConfig,
          eventLog,
          organizations,
          Set(new OwnerPermissionsScopeInitialization(acls, ownerPermissions, serviceAccount)),
          ApiMappings.empty
        )
    } yield projects

}
