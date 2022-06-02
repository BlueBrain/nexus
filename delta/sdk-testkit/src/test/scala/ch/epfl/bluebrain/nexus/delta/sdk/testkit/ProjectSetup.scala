package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.{QuotasDummy, Resources}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
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
    * @param defaultApiMappings
    *   the default api mappings
    */
  def init(
      orgsToCreate: List[Label],
      projectsToCreate: List[Project],
      projectsToDeprecate: List[ProjectRef] = List.empty,
      organizationsToDeprecate: List[Label] = List.empty,
      defaultApiMappings: ApiMappings = Resources.mappings
  )(implicit
      base: BaseUri,
      clock: Clock[UIO],
      uuidf: UUIDF,
      subject: Subject
  ): UIO[(OrganizationsDummy, ProjectsDummy)] = {
    for {
      o <- OrganizationsDummy()
      // Creating organizations
      _ <- orgsToCreate
             .traverse(o.create(_, None))
             .hideErrorsWith(r => new IllegalStateException(r.reason))
      p <- ProjectsDummy(o, QuotasDummy.neverReached, defaultApiMappings)
      // Creating projects
      _ <- projectsToCreate.traverse { c =>
             p.create(c.ref, ProjectGen.projectFields(c))
               .hideErrorsWith(r => new IllegalStateException(r.reason))
           }
      // Deprecating projects
      _ <- projectsToDeprecate
             .traverse { ref =>
               p.deprecate(ref, 1L)
             }
             .hideErrorsWith(r => new IllegalStateException(r.reason))
      // Deprecating orgs
      _ <- organizationsToDeprecate
             .traverse { org =>
               o.deprecate(org, 1L)
             }
             .hideErrorsWith(r => new IllegalStateException(r.reason))
    } yield (o, p)
  }

}
