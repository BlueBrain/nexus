package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import monix.bio.UIO

object ProjectSetup {

  /**
    * Set up Organizations and Projects dummies, populate some data and then eventually apply some deprecation
    *
    * @param orgsToCreate
    *   Organizations to create
    * @param projectsToCreate
    *   Projects to create
    * @param projectsToDeprecate
    *   Projects to deprecate
    * @param organizationsToDeprecate
    *   Organizations to deprecate
    */
  def init(
      orgsToCreate: List[Label],
      projectsToCreate: List[Project],
      projectsToDeprecate: List[ProjectRef] = List.empty,
      organizationsToDeprecate: List[Label] = List.empty
  )(implicit
      subject: Subject
  ): UIO[(Organizations, Projects)] = {
    for {
      o <- UIO.pure(null: Organizations)
      // Creating organizations
      _ <- orgsToCreate
             .traverse(o.create(_, None))
             .hideErrorsWith(r => new IllegalStateException(r.reason))
      p <- UIO.pure(null: Projects)
      // Creating projects
      _ <- projectsToCreate.traverse { c =>
             p.create(c.ref, ProjectGen.projectFields(c))
               .hideErrorsWith(r => new IllegalStateException(r.reason))
           }
      // Deprecating projects
      _ <- projectsToDeprecate
             .traverse { ref =>
               p.deprecate(ref, 1)
             }
             .hideErrorsWith(r => new IllegalStateException(r.reason))
      // Deprecating orgs
      _ <- organizationsToDeprecate
             .traverse { org =>
               o.deprecate(org, 1)
             }
             .hideErrorsWith(r => new IllegalStateException(r.reason))
    } yield (o, p)
  }

}
