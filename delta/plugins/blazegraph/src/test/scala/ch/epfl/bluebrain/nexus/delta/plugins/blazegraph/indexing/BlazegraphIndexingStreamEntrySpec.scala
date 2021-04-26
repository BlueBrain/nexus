package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.MissingPredicate
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import org.apache.jena.graph.Node
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class BlazegraphIndexingStreamEntrySpec extends AnyWordSpecLike with Matchers with TestHelpers with EitherValues {

  "BlazegraphIndexingStreamEntry" should {

    val metadataPredicates: Set[Node] = Set(
      nxv.self.iri,
      nxv.updatedBy.iri,
      nxv.updatedAt.iri,
      nxv.createdBy.iri,
      nxv.createdAt.iri,
      nxv.schemaProject.iri,
      nxv.constrainedBy.iri,
      nxv.incoming.iri,
      nxv.outgoing.iri,
      nxv.rev.iri,
      nxv.deprecated.iri,
      nxv.project.iri
    ).map(Triple.predicate)

    val id = iri"https://example.com/testresource"

    val nQuads = NQuads(contentOf("resource.nq"), id)

    "filter metadata correctly" in {
      val entry = BlazegraphIndexingStreamEntry.fromNQuads(id, nQuads, metadataPredicates).value.resource
      entry.id shouldEqual id
      entry.deprecated shouldEqual false
      entry.schema shouldEqual Latest(iri"https://bluebrain.github.io/nexus/schemas/unconstrained.json")
      entry.types shouldEqual Set(iri"https://example.com/Type1")
      entry.graph.triples.size shouldEqual 2
      entry.metadataGraph.triples.size shouldEqual 12
    }
    "fail when schema predicate is missing" in {
      val nQuadsNoSchema = NQuads(contentOf("resource-no-schema.nq"), id)
      BlazegraphIndexingStreamEntry
        .fromNQuads(id, nQuadsNoSchema, metadataPredicates)
        .left
        .value shouldEqual MissingPredicate(nxv.constrainedBy.iri)
    }
    "fail when deprecated predicate is missing" in {
      val nQuadsNoDeprecated = NQuads(contentOf("resource-no-deprecated.nq"), id)
      BlazegraphIndexingStreamEntry
        .fromNQuads(id, nQuadsNoDeprecated, metadataPredicates)
        .left
        .value shouldEqual MissingPredicate(nxv.deprecated.iri)
    }

  }

}
