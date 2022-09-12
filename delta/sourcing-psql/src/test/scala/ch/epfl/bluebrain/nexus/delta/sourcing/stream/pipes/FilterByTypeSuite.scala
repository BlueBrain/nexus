package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ElemCtx, ReferenceRegistry}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant

class FilterByTypeSuite extends BioSuite {

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
  registry.register(FilterByType)

  def element(types: Set[Iri]): SuccessElem[UniformScopedState] =
    SuccessElem(
      ElemCtx.SourceId(base),
      tpe = PullRequest.entityType,
      id = base / "id",
      rev = 1,
      instant = instant,
      offset = Offset.at(1L),
      value = uniformState.copy(types = types)
    )

  def pipe(types: Set[Iri]): FilterByType =
    registry
      .lookupA[FilterByType.type](FilterByType.reference)
      .rightValue
      .withJsonLdConfig(FilterByTypeConfig(types).toJsonLd)
      .rightValue

  test("Do not filter elements if the expected type set is empty") {
    val elem = element(Set(iri"http://localhost/tpe1"))
    pipe(Set.empty).apply(elem).assert(elem)
  }

  test("Do not filter elements if the expected and elem type set is empty") {
    val elem = element(Set.empty)
    pipe(Set.empty).apply(elem).assert(elem)
  }

  test("Do not filter elements if the type intersection is not void") {
    val elem = element(Set(iri"http://localhost/tpe1", iri"http://localhost/tpe2"))
    pipe(Set(iri"http://localhost/tpe2", iri"http://localhost/tpe3")).apply(elem).assert(elem)
  }

  test("Filter elements if the type intersection is void") {
    val elem = element(Set(iri"http://localhost/tpe1"))
    pipe(Set(iri"http://localhost/tpe2", iri"http://localhost/tpe3")).apply(elem).assert(elem.dropped)
  }
}
