package ch.epfl.bluebrain.nexus.realms

import _root_.io.circe.generic.semiauto._
import _root_.io.circe.{Encoder, Json}
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.syntax.all._
import ch.epfl.bluebrain.nexus.utils.Codecs

/**
  * A realm representation that has been deprecated.
  *
  * @param id           the label of the realm
  * @param name         the name of the realm
  * @param openIdConfig the address of the openid configuration
  * @param logo         an optional logo address
  */
final case class DeprecatedRealm(
    id: RealmLabel,
    name: String,
    openIdConfig: Uri,
    logo: Option[Uri]
)

object DeprecatedRealm extends Codecs {
  implicit val deprecatedEncoder: Encoder[DeprecatedRealm] = {
    val default = deriveEncoder[DeprecatedRealm]
    Encoder
      .instance[DeprecatedRealm] { realm =>
        default(realm) deepMerge Json.obj(
          nxv.label.prefix      -> Json.fromString(realm.id.value),
          nxv.deprecated.prefix -> Json.fromBoolean(true)
        )
      }
      .mapJson(_.removeKeys("id"))
  }
}
