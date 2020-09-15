package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import monix.bio.{IO, Task}

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
  def fetch(label: Label): Task[Option[RealmResource]]

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
  ): Task[UnscoredSearchResults[RealmResource]]

}
