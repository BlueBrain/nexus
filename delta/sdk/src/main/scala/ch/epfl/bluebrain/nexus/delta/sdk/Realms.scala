package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * Operations pertaining to managing realms.
  */
trait Realms {

  /**
    * Creates a new realm using the provided configuration.
    *
    * @param label        the realm label
    * @param name         the name of the realm
    * @param openIdConfig the address of the openid configuration
    * @param logo         an optional realm logo
    */
  def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource]

  /**
    * Updates an existing realm using the provided configuration.
    *
    * @param label        the realm label
    * @param rev          the current revision of the realm
    * @param name         the new name for the realm
    * @param openIdConfig the new openid configuration address
    * @param logo         an optional new logo
    */
  def update(
      label: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource]

  /**
    * Deprecates an existing realm. A deprecated realm prevents clients from authenticating.
    *
    * @param label the id of the realm
    * @param rev   the revision of the realm
    */
  def deprecate(label: Label, rev: Long)(implicit caller: Subject): IO[RealmRejection, RealmResource]

  /**
    * Fetches a realm.
    *
    * @param label the realm label
    */
  def fetch(label: Label): IO[RealmNotFound, RealmResource]

  /**
    * Fetches a realm at a specific revision.
    *
    * @param label the realm label
    * @param rev   the realm revision
    * @return the realm as a resource at the specified revision
    */
  def fetchAt(label: Label, rev: Long): IO[RealmRejection.NotFound, RealmResource]

  /**
    * Lists realms with optional filters.
    *
    * @param pagination the pagination settings
    * @param params     filter parameters of the realms
    * @param ordering   the response ordering
    * @return a paginated results list of realms sorted by their creation date.
    */
  def list(
      pagination: FromPagination,
      params: RealmSearchParams,
      ordering: Ordering[RealmResource]
  ): UIO[UnscoredSearchResults[RealmResource]]

  /**
    * A non terminating stream of events for realms. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[RealmEvent]]

  /**
    * The current realm events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[RealmEvent]]

}

object Realms {

  /**
    * The realms module type.
    */
  final val moduleType: String = "realm"

  private[delta] def next(state: RealmState, event: RealmEvent): RealmState = {
    // format: off
    def created(e: RealmCreated): RealmState = state match {
      case Initial     => Current(e.label, e.rev, deprecated = false, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }
    def updated(e: RealmUpdated): RealmState = state match {
      case Initial    => Initial
      case s: Current => Current(e.label, e.rev, deprecated = false, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, s.createdAt, s.createdBy, e.instant, e.subject)
    }
    def deprecated(e: RealmDeprecated): RealmState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
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
      existingRealms: UIO[Set[RealmResource]]
  )(state: RealmState, cmd: RealmCommand)(implicit
      clock: Clock[UIO] = IO.clock
  ): IO[RealmRejection, RealmEvent] = {
    // format: off
    def create(c: CreateRealm) =
      state match {
        case Initial =>
          openIdAlreadyExists(c.label, c.openIdConfig, existingRealms) >> (wellKnown(c.openIdConfig), IOUtils.instant).mapN {
            case (wk, instant) =>
              RealmCreated(c.label, 1L, c.name, c.openIdConfig, wk.issuer, wk.keys, wk.grantTypes, c.logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, instant, c.subject)
          }
        case _       => IO.raiseError(RealmAlreadyExists(c.label))
      }

    def update(c: UpdateRealm)       =
      state match {
        case Initial                      =>
          IO.raiseError(RealmNotFound(c.label))
        case s: Current if s.rev != c.rev =>
          IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current                   =>
          openIdAlreadyExists(c.label, c.openIdConfig, existingRealms) >> (wellKnown(c.openIdConfig), IOUtils.instant).mapN {
            case (wk, instant) =>
              RealmUpdated(c.label, s.rev + 1, c.name, c.openIdConfig, wk.issuer, wk.keys, wk.grantTypes, c.logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, instant, c.subject)
          }
      }
    // format: on

    def deprecate(c: DeprecateRealm) =
      state match {
        case Initial                      => IO.raiseError(RealmNotFound(c.label))
        case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
        case s: Current if s.deprecated   => IO.raiseError(RealmAlreadyDeprecated(c.label))
        case s: Current                   => IOUtils.instant.map(RealmDeprecated(c.label, s.rev + 1, _, c.subject))
      }

    cmd match {
      case c: CreateRealm    => create(c)
      case c: UpdateRealm    => update(c)
      case c: DeprecateRealm => deprecate(c)
    }
  }

  /**
    * An openId config must not be present for an active or deprecated realms
    */
  private def openIdAlreadyExists(
      excludeLabel: Label,
      matchOpenIdConfig: Uri,
      existingRealms: UIO[Set[RealmResource]]
  ): IO[RealmOpenIdConfigAlreadyExists, Unit] =
    existingRealms.flatMap { resources =>
      val alreadyExists = resources.exists { resource =>
        resource.value.label != excludeLabel && resource.value.openIdConfig == matchOpenIdConfig
      }
      if (alreadyExists)
        IO.raiseError(
          RealmOpenIdConfigAlreadyExists(excludeLabel, matchOpenIdConfig)
        )
      else
        IO.unit
    }

}
