package ch.epfl.bluebrain.nexus.delta.sdk.realms

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.RealmOpenIdConfigAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import doobie.syntax.all.*
import org.http4s.Uri

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
  def apply(xas: Transactors)(self: Label, openIdUri: Uri): IO[Unit] = {
    val id = Realms.encodeId(self)
    sql"SELECT count(id) FROM global_states WHERE type = ${Realms.entityType} AND id != $id AND value->>'openIdConfig' = ${openIdUri.toString()} "
      .query[Int]
      .unique
      .transact(xas.read)
      .flatMap { c =>
        IO.raiseWhen(c > 0)(RealmOpenIdConfigAlreadyExists(self, openIdUri))
      }
  }

}
