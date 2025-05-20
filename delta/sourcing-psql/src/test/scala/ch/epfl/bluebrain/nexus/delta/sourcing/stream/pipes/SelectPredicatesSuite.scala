package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.*
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.SelectPredicates.SelectPredicatesConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class SelectPredicatesSuite extends NexusSuite {

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
  registry.register(SelectPredicates)
  registry.register(DefaultLabelPredicates)

  private val element =
    SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = project,
      instant = instant,
      offset = Offset.at(1L),
      value = graph,
      rev = 1
    )

  private val pullRequestType: Iri = nxv + "PullRequest"
  private val fileType: Iri        = nxv + "File"
  private val fileGraph            = Graph
    .empty(base / "id")
    .add(rdf.tpe, fileType)
    .add(rdfs.label, "File name")
    .add(nxv + "fileSize", 42)
  private def fileElem             =
    element.copy(value = element.value.copy(graph = fileGraph, types = Set(fileType)))

  def pipe(predicates: Set[Iri]): SelectPredicates =
    registry
      .lookupA[SelectPredicates.type](SelectPredicates.ref)
      .rightValue
      .withJsonLdConfig(SelectPredicatesConfig(None, predicates).toJsonLd)
      .rightValue

  def defaultPipe: SelectPredicates =
    registry
      .lookupA[DefaultLabelPredicates.type](DefaultLabelPredicates.ref)
      .rightValue
      .withJsonLdConfig(ExpandedJsonLd.empty)
      .rightValue

  test("Produce an empty graph if the predicate set is empty") {
    val expected = element.copy(value = element.value.copy(graph = Graph.empty(base / "id"), types = Set.empty))
    pipe(Set.empty).apply(element).assertEquals(expected)
  }

  test(s"Retain only the '${rdfs.label}' predicate") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "active")
    val expected = element.copy(value = element.value.copy(graph = graph, types = Set.empty))
    pipe(Set(rdfs.label)).apply(element).assertEquals(expected)
  }

  test(s"Retain the '${rdfs.label}' and the '${rdf.tpe}' predicate with the default pipe") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "active").add(rdf.tpe, pullRequestType)
    val expected = element.copy(value = element.value.copy(graph = graph, types = Set(pullRequestType)))
    defaultPipe.apply(element).assertEquals(expected)
  }

  test(s"Retain only the '${rdfs.label}' predicate for a file if '$fileType' is not a forward type") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "File name")
    val expected = fileElem.copy(value = fileElem.value.copy(graph = graph, types = Set.empty))
    pipe(Set(rdfs.label)).apply(fileElem).assertEquals(expected)
  }

  test(s"Do not apply any modifications for a forward type '$fileType' for the default predicate") {
    defaultPipe.apply(fileElem).assertEquals(fileElem)
  }
}
