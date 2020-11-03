package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectsBehaviors
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.UIO
import org.scalatest.OptionValues

class ProjectImplSpec extends AbstractDBSpec with ProjectsBehaviors with OptionValues with ConfigFixtures {

  val projectsConfig: ProjectsConfig = ProjectsConfig(aggregate, keyValueStore, pagination, indexing)

  override def create: UIO[Projects] =
    for {
      eventLog <- EventLog.postgresEventLog[Envelope[ProjectEvent]](EventLogUtils.toEnvelope).hideErrors
      orgs     <- organizations
      projects <- ProjectsImpl(projectsConfig, eventLog, orgs, acls, ownerPermissions, serviceAccount)
    } yield projects

}
