package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant

class DiscardMetadataSuite extends BioSuite {

  private val base         = iri"http://localhost"
  private val instant      = Instant.now()
  private val state        = PullRequestActive(
    id = Label.unsafe("id"),
    project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj")),
    rev = 1,
    createdAt = instant,
    createdBy = Anonymous,
    updatedAt = instant,
    updatedBy = Anonymous
  )
  private val graph = PullRequestState.toGraphResource(state, base)

  private val registry = new ReferenceRegistry
  registry.register(DiscardMetadata)

  test("Discard metadata graph") {
    val elem     = SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      instant = instant,
      offset = Offset.at(1L),
      value = graph
    )
    val expected = elem.copy(value = graph.copy(metadataGraph = Graph.empty(base / "id")))

    val pipe = registry
      .lookupA[DiscardMetadata.type](DiscardMetadata.reference)
      .rightValue
      .withJsonLdConfig(ExpandedJsonLd.empty)
      .rightValue
    pipe(elem).assert(expected)
  }
}
