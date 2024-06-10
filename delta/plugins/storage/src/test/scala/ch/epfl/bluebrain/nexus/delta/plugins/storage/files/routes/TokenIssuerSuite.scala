package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.JWSSignatureExpired
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes.DelegationResponse
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.testkit.Generators
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.Json
import io.circe.literal.JsonStringContext
import io.circe.syntax.EncoderOps
import munit.CatsEffectSuite

import java.util.Base64
import scala.concurrent.duration.DurationInt

class TokenIssuerSuite extends CatsEffectSuite with Generators {

  val rsaJWK: RSAKey = new RSAKeyGenerator(2048).generate()

  test("JWS verification successfully round trips for identical payloads") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 100.seconds)
    val returnedPayload = genPayload()

    for {
      jwsPayload          <- tokenIssuer.issueJWSPayload(returnedPayload.asJson)
      parsedSignedPayload <- tokenIssuer.verifyJWSPayload(jwsPayload)
    } yield assertEquals(parsedSignedPayload, returnedPayload.asJson)
  }

  def genPayload(): Json =
    json"""
        {
          "bucket": ${genString()},
          "id": ${genString()},
          "path": ${genString()}
        }
          """

  test("JWS verification fails if token is expired") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 0.seconds)
    val returnedPayload = DelegationResponse(genString(), iri"potato", Uri(genString()), None, None)

    val program = for {
      jwsPayload <- tokenIssuer.issueJWSPayload(returnedPayload.asJson)
      _          <- IO.sleep(1.second)
      _          <- tokenIssuer.verifyJWSPayload(jwsPayload)
    } yield ()

    program.intercept[JWSSignatureExpired]
  }

  test("Parsing RSA private key and JWS verification succeed") {
    val base64EncodedKey =
      "LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tTUlJRXZ3SUJBREFOQmdrcWhraUc5dzBCQVFFRkFBU0NCS2t3Z2dTbEFnRUFBb0lCQVFEdXpkaVVDK1k3NEFmS1NETzBoTmlZYWNJZHd6NjdPZXZsaGw4UmlJcTdBNmtibkF2di9CQzE4RXF6VlRqOFhLaWdXMmZvOXoxVk5ZUVNIbnZvWnRxRnNIK000U0QzY3FnMUtrcFduaUxOVWxZUUo3akJLZXg4KzNuSmRqbHpYUmIrVVdIVGJyV1ZGc0tXL3AyYlVPekJxbWF4VzNvZFcxSUlSeFNkVFJUWmtaMStGNDlGRzY0ZmROSnhjNDdMUUwrODhLQS9iMUhIb25kWjdIbGpNOXpyb2ttRUgwaDhxRnlvdmRUdVNkbFRVQTRkOEVMb1FRMTY1N2VlcmJUakVKTEpVUXp5N2lqb2IwakhFMXMvQ2JiS0dUZ1UxL1MwMnY0WklmZExSbXVOMU9QR1QvRWdoM2N3djVZcEc5ZFF3cHg3cVYwSFZaZDJGK0FHRkxnS3ZsaTNBZ01CQUFFQ2dnRUFQRjdkdWMrb1RNcStMVzFEWlFlUW1qZGlVNVBnY0FTY2xsSDZCcnkyRmNFL0p6T3o4TitRZWU1ZGRDaS9WMDAxZEJTbm1FV293N25id1pqalNrVjJTUVh0dVBmUkZiMXV1TUlRT1FXUlZzYlI2eE9mcVhXbnk1RG5vUDY2VjJmWlFFSGlzVWp6cnRVcUxISUI5aG5uUUs2TGQ1cmdyRHRCNmNYT2VGWGNSNFFEZ0x1Wm9wL0NON3lDT3VTbmxHTGhsTklLK3B5QTdLaHZrV1pCdlBoZUdKSWJMdWJHK0x2NElTZW8ydjVINU03SkZtc0FXWDlSYzZNcXMyNThJTHhLMzROdWhGdDdoZWIxK2dGM1BwZTJPMmkrS1o1Z3hKdjJHRHg5VzkyeExNRjA5M21iR3hvMFBmRi93aE5zTGxLR0dkMzJuYWRDQWZNU21MVkFYVUlkQVFLQmdRRDRLZUpRdXdjN25PRzFFMlpyaC9kemw2R2lxck5KWm9IQkcraVF5VThLd1NwV1JFL1RrNUVqMEpXQ0J4Qk0vVk54L3FnZTRwWGVFcy92dSthWk5qMjVidUhoZkE2bXJDRkxCV2l0LzBBbGJsOWRjSlJUYitGRUVtb2ppWGFKWkx3M1RUeEZ4V0tFWGppOFlXcUI3RUNIVzAybnZLcnhBZGpBRDZXNGVseGxrUUtCZ1FEMldFMFFsVTZYK09RTTBBbml1SzN5NXhOcHExMExWQUVBZ1N1S0hLUisxa2txdkVIVWwvUzRsR3Zvamx4OFY1MDJ6Y0lxSWhyQzFHM1hISndaRjE3WWJkNFVlYzhSK1lRU3dXMlgzUW1sMUdNTjUzQlhMZ2pEK1ZQOGEzdmZodUNIQVJKa3MzSFlYZjdweTFvWE1GMHRpSit2NjZSWjYzUVZoVlRHcWU2Vnh3S0JnUUNyditrV3NHb3dFc0tQSEs4Y3FzeFNudFhLQzlQcmI5dExkL0k4Q21iKzdYTk1veGlRT0tnUm5uRnF2VkxGeGVsemtxaHVQNmt6T2RmWmRqVUJRbTN6b1U4SlRGK2pjS3ZXRFJkR25NcWJYVWo1RlVwQ2VNTHg1c0M0ZVpHbFF5ZVVLb3NWU3FlRkx1U2JVOXh2c0w5MExuZVBLRjh5VDNIZ2NyUGgraVZxVVFLQmdRQ0pwMHZnNFYyYWhDU0NtRmw5ekM2L1ZhbytXTmhVTlN1ZUtZKzN6RXVLNkpqWC9YeFhuRlhPTW5tZDZMYjdjRVhVVXVPVmdac3NsV0dQVzFoS21RbVJyTXIwN0IvdWJsd0QwdnczYVBjMEo5cjE4UWFRWUpQYlZsNDg1WjdCaCsrODRMZHpkK1k4dmtGc1NRcGRmTlFFVnB6TXc4TUIwQlQ4MVpWS3NiZzFEd0tCZ1FDVHJ0cCtJekVDQ0hSMHlBTEVsd1JFRGpSTG5RY2MvT21ucWxwNlU5Zy8zNEd5RmRLdUovcTlqWjBCdGVha3ZlYXVucUk2ckpic3puMDdVZktpa2FCK2kzRG1MelphZG9BYjJmL2pTVGhBZS9iUjYwNnd0bExVQTRJRWozb1poWFQrbmVTRWJvRksxSXZScUdtS1VyU1dNSXBKL2ZqV0tuUzBoQW9IYnFkbXBnPT0tLS0tLUVORCBQUklWQVRFIEtFWS0tLS0t"
    val rawKey           = new String(Base64.getDecoder.decode(base64EncodedKey))
    val returnedPayload  = genPayload()

    for {
      parsedPrivateKey <- IO.fromTry(TokenIssuer.parseRSAPrivateKey(rawKey))
      rsaKey            = TokenIssuer.generateRSAKeyFromPrivate(parsedPrivateKey)
      tokenIssuer       = new TokenIssuer(rsaKey, tokenValidity = 100.seconds)
      jwsPayload       <- tokenIssuer.issueJWSPayload(returnedPayload.asJson)
      _                <- tokenIssuer.verifyJWSPayload(jwsPayload)
    } yield ()
  }
}
