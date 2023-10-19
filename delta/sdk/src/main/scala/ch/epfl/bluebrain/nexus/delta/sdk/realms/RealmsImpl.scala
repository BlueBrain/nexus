package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptySet
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.realms.RealmsImpl.RealmsLog
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{RealmNotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._

final class RealmsImpl private (log: RealmsLog) extends Realms {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]]
  )(implicit caller: Subject): IO[RealmResource] = {
    val command = CreateRealm(label, name, openIdConfig, logo, acceptedAudiences, caller)
    eval(command).span("createRealm")
  }

  override def update(
      label: Label,
      rev: Int,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]]
  )(implicit caller: Subject): IO[RealmResource] = {
    val command = UpdateRealm(label, rev, name, openIdConfig, logo, acceptedAudiences, caller)
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
      log.currentStates(_.toResource).translate(ioToTaskK).evalFilter(params.matches),
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
  final def apply(config: RealmsConfig, resolveWellKnown: Uri => IO[WellKnown], xas: Transactors)(implicit
      clock: Clock[IO]
  ): Realms = new RealmsImpl(
    GlobalEventLog(Realms.definition(resolveWellKnown, OpenIdExists(xas)), config.eventLog, xas)
  )

}
