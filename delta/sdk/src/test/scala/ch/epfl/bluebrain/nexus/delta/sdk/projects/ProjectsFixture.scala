package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.{ScopeInitialization, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

import java.util.UUID

trait ProjectsFixture { self: CatsEffectSuite =>

  protected def createProjectsFixture(
      fetchOrgs: FetchActiveOrganization,
      apiMappings: ApiMappings,
      config: ProjectsConfig,
      clock: Clock[IO]
  ): IOFixture[(Transactors, Projects)] = {
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
    implicit val uuidF: UUIDF     = UUIDF.fixed(UUID.randomUUID())
    createProjectsFixture(fetchOrgs, Set.empty, apiMappings, config, clock)
  }

  private def createProjectsFixture(
      fetchOrgs: FetchActiveOrganization,
      scopeInitializations: Set[ScopeInitialization],
      apiMappings: ApiMappings,
      config: ProjectsConfig,
      clock: Clock[IO]
  )(implicit
      base: BaseUri,
      uuidF: UUIDF
  ): IOFixture[(Transactors, Projects)] = {
    val inits = ScopeInitializer.withoutErrorStore(scopeInitializations)
    ResourceSuiteLocalFixture(
      "projects",
      Doobie.resource().map { xas =>
        (xas, ProjectsImpl(fetchOrgs, _ => IO.unit, inits, apiMappings, config.eventLog, xas, clock))
      }
    )
  }

}
