package ch.epfl.bluebrain.nexus.realms

import ch.epfl.bluebrain.nexus.realms.GrantType._
import ch.epfl.bluebrain.nexus.util.EitherValues
import io.circe.{Decoder, Encoder, Json}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors

class GrantTypeSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues {

  "A GrantType" when {
    "using Camel encoders" should {
      import GrantType.Camel._
      val map = Map(
        AuthorizationCode -> "authorizationCode",
        Implicit          -> "implicit",
        Password          -> "password",
        ClientCredentials -> "clientCredentials",
        DeviceCode        -> "deviceCode",
        RefreshToken      -> "refreshToken"
      )
      "be encoded properly" in {
        val encoder = implicitly[Encoder[GrantType]]
        forAll(map.toList) {
          case (gt, expected) =>
            encoder(gt) shouldEqual Json.fromString(expected)
        }
      }
      "be decoded properly" in {
        val decoder = implicitly[Decoder[GrantType]]
        forAll(map.toList) {
          case (expected, gt) =>
            decoder.decodeJson(Json.fromString(gt)).rightValue shouldEqual expected
        }
      }
      "fail to decode for unknown string" in {
        val decoder = implicitly[Decoder[GrantType]]
        decoder.decodeJson(Json.fromString("incorrect")).leftValue
      }
    }
    "using Snake encoders" should {
      import GrantType.Snake._
      val map = Map(
        AuthorizationCode -> "authorization_code",
        Implicit          -> "implicit",
        Password          -> "password",
        ClientCredentials -> "client_credentials",
        DeviceCode        -> "device_code",
        RefreshToken      -> "refresh_token"
      )
      "be encoded properly" in {
        val encoder = implicitly[Encoder[GrantType]]
        forAll(map.toList) {
          case (gt, expected) =>
            encoder(gt) shouldEqual Json.fromString(expected)
        }
      }
      "be decoded properly" in {
        val decoder = implicitly[Decoder[GrantType]]
        forAll(map.toList) {
          case (expected, gtString) =>
            decoder.decodeJson(Json.fromString(gtString)).rightValue shouldEqual expected
        }
      }
      "fail to decode for unknown string" in {
        val decoder = implicitly[Decoder[GrantType]]
        decoder.decodeJson(Json.fromString("incorrect")).leftValue
      }
    }
  }

}
