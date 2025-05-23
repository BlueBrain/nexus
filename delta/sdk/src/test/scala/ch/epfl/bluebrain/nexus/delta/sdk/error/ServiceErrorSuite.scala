package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class ServiceErrorSuite extends NexusSuite with CirceLiteral with Fixtures {

  implicit private val baseUri: BaseUri = BaseUri.unsafe("http://localhost", "v1")

  test("Serialize properly the `AuthorizationFailed`") {
    val error: ServiceError = AuthorizationFailed("Some details")

    val expected =
      json"""
        {
          "@context": "https://bluebrain.github.io/nexus/contexts/error.json",
          "@type": "AuthorizationFailed",
          "reason": "The supplied authentication is not authorized to access this resource.",
          "details": "Some details"
        }"""

    error.toCompactedJsonLd.map(_.json).assertEquals(expected)

  }

}
