package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionSet
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
    val iri                       = iri"http://example.com/permissions"
    implicit val baseUri: BaseUri = BaseUri("http://nexus.com")
    val resource                  =
      ResourceF(
        iri,
        1L,
        Set(nxv.Permissions),
        deprecated = false,
        Instant.EPOCH,
        Anonymous,
        Instant.EPOCH,
        User("maria", Label.unsafe("bbp")),
        Latest(schemas.permissions),
        PermissionSet(Set(acls.read, acls.write))
      )

    implicit val remoteResolution: RemoteContextResolution = RemoteContextResolution.fixed(
      contexts.permissions -> jsonContentOf("contexts/permissions.json"),
      contexts.resource    -> jsonContentOf("contexts/resource.json")
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
