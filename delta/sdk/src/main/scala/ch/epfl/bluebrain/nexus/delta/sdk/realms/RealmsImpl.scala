package ch.epfl.bluebrain.nexus.delta.sdk.realms

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.realms.RealmsImpl.RealmsLog
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{RealmNotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import org.http4s.Uri

final class RealmsImpl private (log: RealmsLog) extends Realms {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      label: Label,
      fields: RealmFields
  )(implicit caller: Subject): IO[RealmResource] = {
    val command = CreateRealm(label, fields.name, fields.openIdConfig, fields.logo, fields.acceptedAudiences, caller)
    eval(command).span("createRealm")
  }

  override def update(
      label: Label,
      rev: Int,
      fields: RealmFields
  )(implicit caller: Subject): IO[RealmResource] = {
    val command =
      UpdateRealm(label, rev, fields.name, fields.openIdConfig, fields.logo, fields.acceptedAudiences, caller)
    eval(command).span("updateRealm")
  }

  override def deprecate(label: Label, rev: Int)(implicit caller: Subject): IO[RealmResource] =
    eval(DeprecateRealm(label, rev, caller)).span("deprecateRealm")

  private def eval(cmd: RealmCommand): IO[RealmResource] =
    log.evaluate(cmd.label, cmd).map(_._2.toResource)

  override def fetch(label: Label): IO[RealmResource] =
    log
      .stateOr(label, RealmNotFound(label))
      .map(_.toResource)
      .span("fetchRealm")

  override def fetchAt(label: Label, rev: Int): IO[RealmResource] =
    log
      .stateOr(label, rev, RealmNotFound(label), RevisionNotFound)
      .map(_.toResource)
      .span("fetchRealmAt")

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.RealmSearchParams,
      ordering: Ordering[RealmResource]
  ): IO[SearchResults.UnscoredSearchResults[RealmResource]] =
    SearchResults(
      log.currentStates(_.toResource).evalFilter(params.matches),
      pagination,
      ordering
    ).span("listRealms")
}

object RealmsImpl {

  type RealmsLog = GlobalEventLog[Label, RealmState, RealmCommand, RealmEvent, RealmRejection]

  /**
    * Constructs a [[Realms]] instance
    *
    * @param config
    *   the realm configuration
    * @param resolveWellKnown
    *   how to resolve the [[WellKnown]]
    * @param xas
    *   the doobie transactors
    */
  final def apply(
      config: RealmsConfig,
      resolveWellKnown: Uri => IO[WellKnown],
      xas: Transactors,
      clock: Clock[IO]
  ): Realms = new RealmsImpl(
    GlobalEventLog(Realms.definition(resolveWellKnown, OpenIdExists(xas), clock), config.eventLog, xas)
  )

}
