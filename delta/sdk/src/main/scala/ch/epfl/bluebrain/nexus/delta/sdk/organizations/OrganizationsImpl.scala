package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsImpl.{OrganizationsLog, UUIDCache}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.{OrganizationCommand, OrganizationEvent, OrganizationRejection, OrganizationState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import monix.bio.{IO, UIO}

import java.util.UUID

final class OrganizationsImpl private (
    log: OrganizationsLog,
    cache: UUIDCache,
    scopeInitializations: Set[ScopeInitialization]
) extends Organizations {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    for {
      resource <- eval(CreateOrganization(label, description, caller)).span("createOrganization")
      _        <- IO.parTraverseUnordered(scopeInitializations)(_.onOrganizationCreation(resource.value, caller))
                    .void
                    .mapError(OrganizationInitializationFailed)
                    .span("initializeOrganization")
    } yield resource

  override def update(
      label: Label,
      description: Option[String],
      rev: Int
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(UpdateOrganization(label, rev, description, caller)).span("updateOrganization")

  override def deprecate(
      label: Label,
      rev: Int
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(DeprecateOrganization(label, rev, caller)).span("deprecateOrganization")

  override def fetch(label: Label): IO[OrganizationNotFound, OrganizationResource] =
    log.stateOr(label, OrganizationNotFound(label)).map(_.toResource).span("fetchOrganization")

  override def fetchAt(label: Label, rev: Int): IO[OrganizationRejection.NotFound, OrganizationResource] = {
    log
      .stateOr(label, rev, OrganizationNotFound(label), RevisionNotFound)
      .map(_.toResource)
      .span("fetchOrganizationAt")
  }

  override def fetch(uuid: UUID): IO[OrganizationNotFound, OrganizationResource] =
    fetchFromCache(uuid).flatMap(fetch).span("fetchOrganizationByUuid")

  override def fetchAt(uuid: UUID, rev: Int): IO[OrganizationRejection.NotFound, OrganizationResource] =
    super.fetchAt(uuid, rev).span("fetchOrganizationAtByUuid")

  private def fetchFromCache(uuid: UUID): IO[OrganizationNotFound, Label] = {
    cache.get(uuid).flatMap {
      case None        =>
        for {
          orgs   <- log.currentStates(o => o.uuid -> o.label).compile.toList.hideErrors
          _      <- cache.putAll(orgs.toMap)
          cached <- cache.getOr(uuid, OrganizationNotFound(uuid))
        } yield cached
      case Some(label) => UIO.pure(label)
    }
  }

  private def eval(cmd: OrganizationCommand): IO[OrganizationRejection, OrganizationResource] =
    log.evaluate(cmd.label, cmd).map(_._2.toResource).tapEval { r =>
      cache.put(r.value.uuid, r.value.label)
    }

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.OrganizationSearchParams,
      ordering: Ordering[OrganizationResource]
  ): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    log
      .currentStates(_.toResource)
      .evalFilter(params.matches)
      .compile
      .toList
      .hideErrors
      .map { resources =>
        SearchResults(
          resources.size.toLong,
          resources.sorted(ordering).slice(pagination.from, pagination.from + pagination.size)
        )
      }
      .span("listOrganizations")

  override def currentEvents(offset: Offset): EnvelopeStream[Label, OrganizationEvent] = log.currentEvents(offset)

  override def events(offset: Offset): EnvelopeStream[Label, OrganizationEvent] = log.events(offset)
}

object OrganizationsImpl {

  type OrganizationsLog =
    GlobalEventLog[Label, OrganizationState, OrganizationCommand, OrganizationEvent, OrganizationRejection]

  type UUIDCache = KeyValueStore[UUID, Label]

  def apply(
      scopeInitializations: Set[ScopeInitialization],
      config: OrganizationsConfig,
      xas: Transactors
  )(implicit
      clock: Clock[UIO] = IO.clock,
      uuidf: UUIDF
  ): UIO[Organizations] =
    KeyValueStore.localLRU[UUID, Label](config.cacheMaxSize.toLong).map { cache =>
      new OrganizationsImpl(
        GlobalEventLog(Organizations.definition, config.eventLog, xas),
        cache,
        scopeInitializations
      )
    }

}
