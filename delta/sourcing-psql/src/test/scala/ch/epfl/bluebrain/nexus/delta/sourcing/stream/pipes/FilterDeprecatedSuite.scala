package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

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

class FilterDeprecatedSuite extends BioSuite {

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

  private val graph = PullRequestState.toGraphResource(state, base)

  private val registry = new ReferenceRegistry
  registry.register(FilterDeprecated)

  def pipe: FilterDeprecated =
    registry
      .lookupA[FilterDeprecated.type](FilterDeprecated.ref)
      .rightValue
      .withJsonLdConfig(ExpandedJsonLd.empty)
      .rightValue

  test("Drop deprecated elements") {
    val elem = SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = Some(project),
      instant = instant,
      offset = Offset.at(1L),
      value = graph.copy(deprecated = true),
      revision = 1
    )

    pipe(elem).assert(elem.dropped)
  }

  test("Preserve non-deprecated elements") {
    val elem = SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = Some(project),
      instant = instant,
      offset = Offset.at(1L),
      value = graph,
      revision = 1
    )

    pipe(elem).assert(elem)
  }
}
