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
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class DiscardMetadataSuite extends NexusSuite {

  private val base    = iri"http://localhost"
  private val instant = Instant.now()
  private val project = ProjectRef(Label.unsafe("org"), Label.unsafe("proj"))
  private val state   = PullRequestActive(
    id = base / "id",
    project = project,
    rev = 1,
    createdAt = instant,
    createdBy = Anonymous,
    updatedAt = instant,
    updatedBy = Anonymous
  )
  private val graph   = PullRequestState.toGraphResource(state, base)

  private val registry = new ReferenceRegistry
  registry.register(DiscardMetadata)

  test("Discard metadata graph") {
    val elem     = SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = project,
      instant = instant,
      offset = Offset.at(1L),
      value = graph,
      rev = 1
    )
    val expected = elem.copy(value = graph.copy(metadataGraph = Graph.empty(base / "id")))

    val pipe = registry
      .lookupA[DiscardMetadata.type](DiscardMetadata.ref)
      .rightValue
      .withJsonLdConfig(ExpandedJsonLd.empty)
      .rightValue
    pipe(elem).assertEquals(expected)
  }
}
