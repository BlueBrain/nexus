package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectsBehaviors
import ch.epfl.bluebrain.nexus.delta.service.utils.{ApplyOwnerPermissions, EventLogUtils}
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.UIO

class ProjectsImplSpec extends AbstractDBSpec with ProjectsBehaviors with ConfigFixtures {

  val projectsConfig: ProjectsConfig = ProjectsConfig(aggregate, keyValueStore, pagination, indexing)

  override def create: UIO[Projects] =
    for {
      eventLog <- EventLog.postgresEventLog[Envelope[ProjectEvent]](EventLogUtils.toEnvelope).hideErrors
      projects <-
        ProjectsImpl(
          projectsConfig,
          eventLog,
          organizations,
          ApplyOwnerPermissions(acls, ownerPermissions, serviceAccount)
        )
    } yield projects

}
