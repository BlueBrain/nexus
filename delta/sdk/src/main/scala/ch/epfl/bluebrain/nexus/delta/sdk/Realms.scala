package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{RealmCommand, RealmEvent, RealmRejection, RealmState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils
import monix.bio.{IO, UIO}

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
  def create(label: Label, name: Name, openIdConfig: Uri, logo: Option[Uri]): IO[RealmRejection, RealmResource]

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
  ): IO[RealmRejection, RealmResource]

  /**
    * Deprecates an existing realm. A deprecated realm prevents clients from authenticating.
    *
    * @param label the id of the realm
    * @param rev   the revision of the realm
    */
  def deprecate(label: Label, rev: Long): IO[RealmRejection, RealmResource]

  /**
    * Fetches a realm.
    *
    * @param label the realm label
    * @return the realm in a Resource representation, None otherwise
    */
  def fetch(label: Label): UIO[Option[RealmResource]]

  /**
    * Fetches a realm at a specific revision.
    *
    * @param label the realm label
    * @param rev   the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  def fetchAt(label: Label, rev: Long): IO[RevisionNotFound, Option[RealmResource]]

  /**
    * Lists realms with optional filters.
    *
    * @param pagination the pagination settings
    * @param params filter parameters of the realms
    * @return a paginated results list of realms sorted by their creation date.
    */
  def list(
      pagination: FromPagination,
      params: RealmSearchParams = RealmSearchParams.none
  ): UIO[UnscoredSearchResults[RealmResource]]

}

object Realms {
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

  private[delta] def evaluate(wellKnown: WellKnownResolver)(state: RealmState, cmd: RealmCommand)(implicit
      clock: Clock[UIO] = IO.clock
  ): IO[RealmRejection, RealmEvent] = {
    // format: off
    def create(c: CreateRealm) =
      state match {
        case Initial =>
          (wellKnown(c.openIdConfig), IOUtils.instant).mapN {
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
          (wellKnown(c.openIdConfig), IOUtils.instant).mapN {
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

}
