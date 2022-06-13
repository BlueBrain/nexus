package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.RealmOpenIdConfigAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import doobie.implicits._
import monix.bio.IO

object OpenIdExists {

  /**
    * Check for the existence of another realm with the matching openId uri
    * @param xas
    *   the doobie transactor
    * @param self
    *   the label of the current realm
    * @param openIdUri
    *   the openId uri to check for
    */
  def apply(xas: Transactors)(self: Label, openIdUri: Uri): IO[RealmOpenIdConfigAlreadyExists, Unit] =
    sql"SELECT count(id) FROM global_states WHERE type = ${Realms.entityType} AND id != $self AND value->>'openIdConfig' = ${openIdUri.toString()} "
      .query[Int]
      .unique
      .transact(xas.read)
      .hideErrors
      .flatMap { c =>
        IO.raiseWhen(c > 0)(RealmOpenIdConfigAlreadyExists(self, openIdUri))
      }

}
