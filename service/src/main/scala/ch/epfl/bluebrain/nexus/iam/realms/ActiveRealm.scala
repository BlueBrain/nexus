package ch.epfl.bluebrain.nexus.iam.realms

import ch.epfl.bluebrain.nexus.iam.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.types.GrantType.Camel._
import ch.epfl.bluebrain.nexus.iam.types.{GrantType, Label}
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.nimbusds.jose.jwk.{JWK, JWKSet}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import scala.util.Try

/**
  * An active realm representation.
  *
  * @param id                    the label of the realm
  * @param name                  the name of the realm
  * @param openIdConfig          the address of the openid configuration
  * @param issuer                an identifier for the issuer
  * @param grantTypes            the supported grant types of the realm
  * @param logo                  an optional logo address
  * @param authorizationEndpoint the authorization endpoint
  * @param tokenEndpoint         the token endpoint
  * @param userInfoEndpoint      the user info endpoint
  * @param revocationEndpoint    an optional revocation endpoint
  * @param endSessionEndpoint    an optional end session endpoint
  * @param keys                  the set of JWK keys as specified by rfc 7517 (https://tools.ietf.org/html/rfc7517)
  */
final case class ActiveRealm(
    id: Label,
    name: String,
    openIdConfig: Url,
    issuer: String,
    grantTypes: Set[GrantType],
    logo: Option[Url],
    authorizationEndpoint: Url,
    tokenEndpoint: Url,
    userInfoEndpoint: Url,
    revocationEndpoint: Option[Url],
    endSessionEndpoint: Option[Url],
    keys: Set[Json]
) {

  private[realms] lazy val keySet: JWKSet = {
    val jwks = keys.foldLeft(Set.empty[JWK]) {
      case (acc, e) => Try(JWK.parse(e.noSpaces)).map(acc + _).getOrElse(acc)
    }
    import scala.jdk.CollectionConverters._
    new JWKSet(jwks.toList.asJava)
  }
}

object ActiveRealm {
  implicit private[ActiveRealm] val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "issuer"                => nxv.issuer.prefix
    case "grantTypes"            => nxv.grantTypes.prefix
    case "authorizationEndpoint" => nxv.authorizationEndpoint.prefix
    case "tokenEndpoint"         => nxv.tokenEndpoint.prefix
    case "userInfoEndpoint"      => nxv.userInfoEndpoint.prefix
    case "revocationEndpoint"    => nxv.revocationEndpoint.prefix
    case "endSessionEndpoint"    => nxv.endSessionEndpoint.prefix
    case other                   => other
  })
  implicit val activeEncoder: Encoder[ActiveRealm] = {
    val default = deriveConfiguredEncoder[ActiveRealm]
    Encoder
      .instance[ActiveRealm] { realm =>
        default(realm) deepMerge Json.obj(
          nxv.label.prefix      -> Json.fromString(realm.id.value),
          nxv.deprecated.prefix -> Json.fromBoolean(false)
        )
      }
      .mapJson(_.removeKeys("keys", "id"))
  }
}
