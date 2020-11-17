package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceFSpec
    extends AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with TestHelpers
    with IOValues
    with TestMatchers {

  "A ResourceF" should {
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
    val updatedBy                 = User("maria", Label.unsafe("bbp"))
    val resource                  = PermissionsGen.resourceFor(Set(acls.read, acls.write), rev = 1L, updatedBy = updatedBy)

    implicit val remoteResolution: RemoteContextResolution = RemoteContextResolution.fixed(
      contexts.permissions -> jsonContentOf("contexts/permissions.json"),
      contexts.metadata    -> jsonContentOf("contexts/metadata.json")
    )

    "be converted to Json-LD compacted" in {
      resource.toCompactedJsonLd.accepted.json shouldEqual jsonContentOf("resource-compacted.jsonld")
    }

    "be converted to Json-LD expanded" in {
      resource.toExpandedJsonLd.accepted.json shouldEqual jsonContentOf("resource-expanded.jsonld")
    }

    "be converted to Dot format" in {
      resource.toDot.accepted.toString should equalLinesUnordered(contentOf("resource-dot.dot"))

    }

    "be converted to NTriples format" in {
      resource.toNTriples.accepted.toString should equalLinesUnordered(contentOf("resource-ntriples.nt"))
    }
  }

}
