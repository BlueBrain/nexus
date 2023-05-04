package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import monix.bio.UIO

import java.util.UUID

object ProjectsFixture {

  def init(fetchOrgs: FetchOrganization, apiMappings: ApiMappings, config: ProjectsConfig)(implicit
      clock: Clock[UIO],
      cl: ClassLoader
  ): ResourceFixture.TaskFixture[(Transactors, Projects)] = {
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
    implicit val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())
    init(fetchOrgs, Set.empty, apiMappings, config)
  }

  def init(
      fetchOrgs: FetchOrganization,
      scopeInitializations: Set[ScopeInitialization],
      apiMappings: ApiMappings,
      config: ProjectsConfig
  )(implicit
      base: BaseUri,
      clock: Clock[UIO],
      uuidF: UUIDF,
      cl: ClassLoader
  ): ResourceFixture.TaskFixture[(Transactors, Projects)] =
    ResourceFixture.suiteLocal(
      "projects",
      Doobie.resource().map { xas =>
        (xas, ProjectsImpl(fetchOrgs, _ => UIO.unit, scopeInitializations, apiMappings, config, xas))
      }
    )

}
