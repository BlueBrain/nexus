package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name, SearchParams}
import monix.bio.{IO, Task}

/**
  * Operations pertaining to realms.
  */
trait Realms {

  /**
    * Creates a new realm using the provided configuration.
    *
    * @param id           the realm id
    * @param name         the name of the realm
    * @param openIdConfig the address of the openid configuration
    * @param logo         an optional realm logo
    */
  def create(id: Label, name: Name, openIdConfig: Uri, logo: Option[Uri]): IO[RealmRejection, RealmResource]

  /**
    * Updates an existing realm using the provided configuration.
    *
    * @param id           the realm id
    * @param rev          the current revision of the realm
    * @param name         the new name for the realm
    * @param openIdConfig the new openid configuration address
    * @param logo         an optional new logo
    */
  def update(id: Label, rev: Long, name: Name, openIdConfig: Uri, logo: Option[Uri]): IO[RealmRejection, RealmResource]

  /**
    * Deprecates an existing realm. A deprecated realm prevents clients from authenticating.
    *
    * @param id  the id of the realm
    * @param rev the revision of the realm
    */
  def deprecate(id: Label, rev: Long): IO[RealmRejection, RealmResource]

  /**
    * Fetches a realm.
    *
    * @param id the id of the realm
    * @return the realm in a Resource representation, None otherwise
    */
  def fetch(id: Label): Task[Option[RealmResource]]

  /**
    * Fetches a realm at a specific revision.
    *
    * @param rev the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  def fetchAt(rev: Long): IO[RevisionNotFound, RealmResource]

  /**
    * Lists realms with optional filters.
    *
    * @param params filter parameters of the realms
    * @return the current realms sorted by their creation date.
    */
  def list(params: SearchParams = SearchParams.none): Task[List[RealmResource]]

}
