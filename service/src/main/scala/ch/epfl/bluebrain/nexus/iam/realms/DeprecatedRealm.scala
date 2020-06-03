package ch.epfl.bluebrain.nexus.iam.realms

import _root_.io.circe.generic.semiauto._
import _root_.io.circe.{Encoder, Json}
import ch.epfl.bluebrain.nexus.iam.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.types.Label
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.implicits._

/**
  * A realm representation that has been deprecated.
  *
  * @param id           the label of the realm
  * @param name         the name of the realm
  * @param openIdConfig the address of the openid configuration
  * @param logo         an optional logo address
  */
final case class DeprecatedRealm(
    id: Label,
    name: String,
    openIdConfig: Url,
    logo: Option[Url]
)

object DeprecatedRealm {
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
