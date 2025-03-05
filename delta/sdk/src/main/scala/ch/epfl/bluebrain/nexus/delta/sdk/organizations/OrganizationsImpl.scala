package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsImpl.OrganizationsLog
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{OrganizationCommand, OrganizationEvent, OrganizationRejection, OrganizationState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

final class OrganizationsImpl private (
    log: OrganizationsLog,
    scopeInitializer: ScopeInitializer
) extends Organizations {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationResource] =
    for {
      resource <- eval(CreateOrganization(label, description, caller)).span("createOrganization")
      _        <- scopeInitializer
                    .initializeOrganization(resource)
                    .span("initializeOrganization")
    } yield resource

  override def update(
      label: Label,
      description: Option[String],
      rev: Int
  )(implicit caller: Subject): IO[OrganizationResource] =
    eval(UpdateOrganization(label, rev, description, caller)).span("updateOrganization")

  override def deprecate(
      label: Label,
      rev: Int
  )(implicit caller: Subject): IO[OrganizationResource] =
    eval(DeprecateOrganization(label, rev, caller)).span("deprecateOrganization")

  override def undeprecate(org: Label, rev: Int)(implicit caller: Subject): IO[OrganizationResource] = {
    eval(UndeprecateOrganization(org, rev, caller)).span("undeprecateOrganization")
  }

  override def fetch(label: Label): IO[OrganizationResource] =
    log.stateOr(label, OrganizationNotFound(label)).map(_.toResource).span("fetchOrganization")

  override def fetchAt(label: Label, rev: Int): IO[OrganizationResource] = {
    log
      .stateOr(label, rev, OrganizationNotFound(label), RevisionNotFound)
      .map(_.toResource)
      .span("fetchOrganizationAt")
  }

  private def eval(cmd: OrganizationCommand): IO[OrganizationResource] =
    log.evaluate(cmd.label, cmd).map(_._2.toResource)

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.OrganizationSearchParams,
      ordering: Ordering[OrganizationResource]
  ): IO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    SearchResults(
      log
        .currentStates(_.toResource)
        .evalFilter(params.matches),
      pagination,
      ordering
    ).span("listOrganizations")

  override def purge(org: Label): IO[Unit] = log.delete(org)
}

object OrganizationsImpl {

  type OrganizationsLog =
    GlobalEventLog[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection]

  def apply(
      scopeInitializer: ScopeInitializer,
      config: EventLogConfig,
      xas: Transactors,
      clock: Clock[IO]
  )(implicit
      uuidf: UUIDF
  ): Organizations =
    new OrganizationsImpl(
      GlobalEventLog(Organizations.definition(clock), config, xas),
      scopeInitializer
    )

}
