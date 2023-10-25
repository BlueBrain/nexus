package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
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
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite

import java.time.Instant

class SelectPredicatesSuite extends BioSuite {

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
      project = Some(project),
      instant = instant,
      offset = Offset.at(1L),
      value = graph,
      rev = 1
    )

  private val pullRequestType: Iri = nxv + "PullRequest"
  private val fileType: Iri        = nxv + "File"
  private val fileElem             = element.copy(value =
    element.value.copy(graph = graph.graph.add(rdf.tpe, fileType), types = Set(pullRequestType, fileType))
  )

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
    pipe(Set.empty).apply(element).assert(expected)
  }

  test(s"Retain only the '${rdfs.label}' predicate") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "active")
    val expected = element.copy(value = element.value.copy(graph = graph, types = Set.empty))
    pipe(Set(rdfs.label)).apply(element).assert(expected)
  }

  test(s"Retain the '${rdfs.label}' and the '${rdf.tpe}' predicate with the default pipe") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "active").add(rdf.tpe, pullRequestType)
    val expected = element.copy(value = element.value.copy(graph = graph, types = Set(pullRequestType)))
    defaultPipe.apply(element).assert(expected)
  }

  test(s"Retain only the '${rdfs.label}' predicate for a file if '$fileType' is not a forward type") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "active")
    val expected = fileElem.copy(value = fileElem.value.copy(graph = graph, types = Set.empty))
    pipe(Set(rdfs.label)).apply(fileElem).assert(expected)
  }

  test(s"Do not apply any modifications for a forward type '$fileType' for the default predicate") {
    defaultPipe.apply(fileElem).assert(fileElem)
  }
}
