package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Name, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{IncorrectRev, RealmAlreadyDeprecated, RealmAlreadyExists, RealmNotFound, RealmOpenIdConfigAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, EnvelopeStream, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDefinition, StateMachine}
import monix.bio.{IO, UIO}

/**
  * Operations pertaining to managing realms.
  */
trait Realms {

  /**
    * Creates a new realm using the provided configuration.
    *
    * @param label
    *   the realm label
    * @param name
    *   the name of the realm
    * @param openIdConfig
    *   the address of the openid configuration
    * @param logo
    *   an optional realm logo
    * @param acceptedAudiences
    *   the optional set of audiences of this realm. JWT with `aud` which do not match this field will be rejected
    */
  def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource]

  /**
    * Updates an existing realm using the provided configuration.
    *
    * @param label
    *   the realm label
    * @param rev
    *   the current revision of the realm
    * @param name
    *   the new name for the realm
    * @param openIdConfig
    *   the new openid configuration address
    * @param logo
    *   an optional new logo
    * @param acceptedAudiences
    *   the optional set of audiences of this realm. JWT with `aud` which do not match this field will be rejected
    */
  def update(
      label: Label,
      rev: Int,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource]

  /**
    * Deprecates an existing realm. A deprecated realm prevents clients from authenticating.
    *
    * @param label
    *   the id of the realm
    * @param rev
    *   the revision of the realm
    */
  def deprecate(label: Label, rev: Int)(implicit caller: Subject): IO[RealmRejection, RealmResource]

  /**
    * Fetches a realm.
    *
    * @param label
    *   the realm label
    */
  def fetch(label: Label): IO[RealmNotFound, RealmResource]

  /**
    * Fetches a realm at a specific revision.
    *
    * @param label
    *   the realm label
    * @param rev
    *   the realm revision
    * @return
    *   the realm as a resource at the specified revision
    */
  def fetchAt(label: Label, rev: Int): IO[RealmRejection.NotFound, RealmResource]

  /**
    * Lists realms with optional filters.
    *
    * @param pagination
    *   the pagination settings
    * @param params
    *   filter parameters of the realms
    * @param ordering
    *   the response ordering
    * @return
    *   a paginated results list of realms sorted by their creation date.
    */
  def list(
      pagination: FromPagination,
      params: RealmSearchParams,
      ordering: Ordering[RealmResource]
  ): UIO[UnscoredSearchResults[RealmResource]]

  /**
    * A non terminating stream of events for realms. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = Offset.Start): EnvelopeStream[Label, RealmEvent]

  /**
    * The current realm events. The stream stops after emitting all known events.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = Offset.Start): EnvelopeStream[Label, RealmEvent]
}

object Realms {

  /**
    * The realms module type.
    */
  final val entityType: EntityType = EntityType("realm")

  private[delta] def next(state: Option[RealmState], event: RealmEvent): Option[RealmState] = {
    // format: off
    def created(e: RealmCreated): Option[RealmState] =
      Option.when(state.isEmpty) {
        RealmState(e.label, e.rev, deprecated = false, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.acceptedAudiences, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, e.instant, e.subject, e.instant, e.subject)
      }

    def updated(e: RealmUpdated): Option[RealmState] = state.map { s =>
      RealmState(e.label, e.rev, deprecated = false, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.acceptedAudiences, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, s.createdAt, s.createdBy, e.instant, e.subject)
    }

    def deprecated(e: RealmDeprecated): Option[RealmState] = state.map {
      _.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    event match {
      case e: RealmCreated    => created(e)
      case e: RealmUpdated    => updated(e)
      case e: RealmDeprecated => deprecated(e)
    }
  }

  private[delta] def evaluate(
      wellKnown: Uri => IO[RealmRejection, WellKnown],
      openIdExists: (Label, Uri) => IO[RealmOpenIdConfigAlreadyExists, Unit]
  )(state: Option[RealmState], cmd: RealmCommand)(implicit
      clock: Clock[UIO] = IO.clock
  ): IO[RealmRejection, RealmEvent] = {
    // format: off
    def create(c: CreateRealm) =
      state.fold {
        openIdExists(c.label, c.openIdConfig) >> (wellKnown(c.openIdConfig), IOUtils.instant).mapN {
          case (wk, instant) =>
            RealmCreated(c.label, c.name, c.openIdConfig, c.logo, c.acceptedAudiences, wk, instant, c.subject)
        }
      }(_ => IO.raiseError(RealmAlreadyExists(c.label)))

    def update(c: UpdateRealm)       =
      IO.fromOption(
        state,
        RealmNotFound(c.label)
      ).flatMap {
        case s if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s => openIdExists(c.label, c.openIdConfig) >> (wellKnown(c.openIdConfig), IOUtils.instant).mapN {
          case (wk, instant) =>
            RealmUpdated(c.label, s.rev + 1, c.name, c.openIdConfig, c.logo, c.acceptedAudiences, wk, instant, c.subject)
        }
      }
    // format: on

    def deprecate(c: DeprecateRealm) =
      IO.fromOption(
        state,
        RealmNotFound(c.label)
      ).flatMap {
        case s if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s if s.deprecated   => IO.raiseError(RealmAlreadyDeprecated(c.label))
        case s                   => IOUtils.instant.map(RealmDeprecated(c.label, s.rev + 1, _, c.subject))
      }

    cmd match {
      case c: CreateRealm    => create(c)
      case c: UpdateRealm    => update(c)
      case c: DeprecateRealm => deprecate(c)
    }
  }

  /**
    * Entity definition for [[Permissions]]
    *
    * @param wellKnown
    *   how to extract the well known configuration
    * @param existingRealms
    *   the existing realms
    */
  def definition(
      wellKnown: Uri => IO[RealmRejection, WellKnown],
      openIdExists: (Label, Uri) => IO[RealmOpenIdConfigAlreadyExists, Unit]
  )(implicit
      clock: Clock[UIO]
  ): EntityDefinition[Label, RealmState, RealmCommand, RealmEvent, RealmRejection] =
    EntityDefinition.untagged(
      entityType,
      StateMachine(None, evaluate(wellKnown, openIdExists), next),
      RealmEvent.serializer,
      RealmState.serializer,
      onUniqueViolation = (id: Label, c: RealmCommand) =>
        c match {
          case _: CreateRealm    => RealmAlreadyExists(id)
          case u: UpdateRealm    => IncorrectRev(u.rev, u.rev + 1)
          case d: DeprecateRealm => IncorrectRev(d.rev, d.rev + 1)
        }
    )

}
