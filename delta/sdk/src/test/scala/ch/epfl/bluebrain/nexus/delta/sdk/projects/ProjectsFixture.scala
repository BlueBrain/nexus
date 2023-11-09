package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture

import java.util.UUID

object ProjectsFixture {

  def init(fetchOrgs: FetchOrganization, apiMappings: ApiMappings, config: ProjectsConfig)(implicit
      clock: Clock[IO],
      timer: Timer[IO],
      cs: ContextShift[IO],
      cl: ClassLoader
  ): IOFixture[(Transactors, Projects)] = {
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
      clock: Clock[IO],
      cs: ContextShift[IO],
      timer: Timer[IO],
      uuidF: UUIDF,
      cl: ClassLoader
  ): IOFixture[(Transactors, Projects)] =
    ResourceFixture.suiteLocal(
      "projects",
      Doobie.resource().map { xas =>
        (xas, ProjectsImpl(fetchOrgs, _ => IO.unit, scopeInitializations, apiMappings, config, xas))
      }
    )

}
