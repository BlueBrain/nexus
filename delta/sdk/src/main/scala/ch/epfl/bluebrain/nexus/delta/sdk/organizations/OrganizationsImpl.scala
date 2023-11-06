package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.IO._
import cats.effect.{Clock, ContextShift, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsImpl.OrganizationsLog
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{OrganizationCommand, OrganizationEvent, OrganizationRejection, OrganizationState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

final class OrganizationsImpl private (
    log: OrganizationsLog,
    scopeInitializations: Set[ScopeInitialization]
)(implicit contextShift: ContextShift[IO])
    extends Organizations {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationResource] =
    for {
      resource <- eval(CreateOrganization(label, description, caller)).span("createOrganization")
      _        <- scopeInitializations
                    .parUnorderedTraverse(_.onOrganizationCreation(resource.value, caller))
                    .void
                    .adaptError { case e: ScopeInitializationFailed =>
                      OrganizationInitializationFailed(e)
                    }
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
        .translate(ioToTaskK)
        .evalFilter(params.matches(_).toUIO),
      pagination,
      ordering
    ).span("listOrganizations")
}

object OrganizationsImpl {

  type OrganizationsLog =
    GlobalEventLog[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection]

  def apply(
      scopeInitializations: Set[ScopeInitialization],
      config: OrganizationsConfig,
      xas: Transactors
  )(implicit
      clock: Clock[IO],
      uuidf: UUIDF,
      contextShift: ContextShift[IO],
      timer: Timer[IO]
  ): Organizations =
    new OrganizationsImpl(
      GlobalEventLog(Organizations.definition, config.eventLog, xas),
      scopeInitializations
    )

}
