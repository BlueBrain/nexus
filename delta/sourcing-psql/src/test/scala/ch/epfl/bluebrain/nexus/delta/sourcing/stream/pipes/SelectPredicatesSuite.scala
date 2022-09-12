package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdfs}
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
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.SelectPredicates.SelectPredicatesConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ElemCtx, ReferenceRegistry}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant

class SelectPredicatesSuite extends BioSuite {

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
  private val uniformState =
    PullRequestState.uniformScopedStateEncoder(base).toUniformScopedState(state).runSyncUnsafe(ioTimeout)

  private val registry = new ReferenceRegistry
  registry.register(SelectPredicates)
  registry.register(DefaultLabelPredicates)

  private val element =
    SuccessElem(
      ElemCtx.SourceId(base),
      tpe = PullRequest.entityType,
      id = base / "id",
      rev = 1,
      instant = instant,
      offset = Offset.at(1L),
      value = uniformState
    )

  def pipe(predicates: Set[Iri]): SelectPredicates =
    registry
      .lookupA[SelectPredicates.type](SelectPredicates.reference)
      .rightValue
      .withJsonLdConfig(SelectPredicatesConfig(predicates).toJsonLd)
      .rightValue

  def defaultPipe: SelectPredicates =
    registry
      .lookupA[DefaultLabelPredicates.type](DefaultLabelPredicates.reference)
      .rightValue
      .withJsonLdConfig(ExpandedJsonLd.empty)
      .rightValue

  test("Produce an empty graph if the predicate set is empty") {
    val expected = element.copy(value = element.value.copy(graph = Graph.empty(base / "id"), types = Set.empty))
    pipe(Set.empty).apply(element).assert(expected)
  }

  test("Retain matching predicates") {
    val graph    = Graph.empty(base / "id").add(rdfs.label, "active").add(Vocabulary.rdf.tpe, nxv + "PullRequest")
    val types    = Set(nxv + "PullRequest")
    val expected = element.copy(value = element.value.copy(graph = graph, types = types))
    pipe(Set(rdfs.label)).apply(element).assert(expected)
    defaultPipe.apply(element).assert(expected)
  }
}
