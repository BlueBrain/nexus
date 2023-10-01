package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toCatsIO
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.ce.CatsIOValues
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, TestHelpers}
import io.circe.syntax.EncoderOps
import monix.bio.UIO
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdDocumentSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with CatsIOValues
    with OptionValues
    with ContextFixtures
    with CirceEq {
  "A JsonLdDocument" should {
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    val input                         = jsonContentOf("reconstructed-cell.json")
    val expanded                      = toCatsIO(ExpandedJsonLd(input)).accepted

    "be generated from expanded Json resource" in {
      val nodeRef1                                   = iri"http://api.brain-map.org/api/v2/data/Structure/733"
      val nodeRef2                                   = iri"http://localhost/nexus/v1/files/my-file"
      def findRelationships: UIO[Map[Iri, Set[Iri]]] = UIO.pure(
        Map(
          nodeRef1 -> Set(iri"https://neuroshapes.org/NeuronMorphology"),
          nodeRef2 -> Set(nxvFile)
        )
      )
      val document                                   = JsonLdDocument.fromExpanded(expanded, _ => findRelationships)
      document.accepted.asJson should equalIgnoreArrayOrder(jsonContentOf("reconstructed-cell-document.json"))
    }
  }

}
