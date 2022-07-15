package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms.entityType
import ch.epfl.bluebrain.nexus.delta.sdk.realms.RealmsImpl.RealmsLog
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{RealmNotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import monix.bio.{IO, UIO}

final class RealmsImpl private (log: RealmsLog) extends Realms {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  override def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] = {
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
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] = {
    val command = UpdateRealm(label, rev, name, openIdConfig, logo, acceptedAudiences, caller)
    eval(command).span("updateRealm")
  }

  override def deprecate(label: Label, rev: Int)(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller)).span("deprecateRealm")

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    log.evaluate(cmd.label, cmd).map(_._2.toResource)

  override def fetch(label: Label): IO[RealmNotFound, RealmResource] =
    log
      .stateOr(label, RealmNotFound(label))
      .map(_.toResource)
      .span("fetchRealm")

  override def fetchAt(label: Label, rev: Int): IO[RealmRejection.NotFound, RealmResource] =
    log
      .stateOr(label, rev, RealmNotFound(label), RevisionNotFound)
      .map(_.toResource)
      .span("fetchRealmAt")

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.RealmSearchParams,
      ordering: Ordering[RealmResource]
  ): UIO[SearchResults.UnscoredSearchResults[RealmResource]] =
    SearchResults(
      log.currentStates(_.toResource).evalFilter(params.matches),
      pagination,
      ordering
    ).span("listRealms")

  override def events(offset: Offset): EnvelopeStream[Label, RealmEvent] =
    log.events(offset)

  override def currentEvents(offset: Offset): EnvelopeStream[Label, RealmEvent] =
    log.currentEvents(offset)
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
  final def apply(config: RealmsConfig, resolveWellKnown: Uri => IO[RealmRejection, WellKnown], xas: Transactors)(
      implicit clock: Clock[UIO]
  ): Realms = new RealmsImpl(
    GlobalEventLog(Realms.definition(resolveWellKnown, OpenIdExists(xas)), config.eventLog, xas)
  )

}
