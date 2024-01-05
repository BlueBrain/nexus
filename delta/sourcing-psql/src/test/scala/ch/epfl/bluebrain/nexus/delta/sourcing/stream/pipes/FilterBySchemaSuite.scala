package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef, ViewRestriction}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterBySchema.FilterBySchemaConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.time.Instant

class FilterBySchemaSuite extends NexusSuite {

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
  registry.register(FilterBySchema)

  def element(schema: ResourceRef): SuccessElem[GraphResource] =
    SuccessElem(
      tpe = PullRequest.entityType,
      id = base / "id",
      project = Some(project),
      instant = instant,
      offset = Offset.at(1L),
      value = graph.copy(schema = schema),
      rev = 1
    )

  def pipe(schemas: Set[Iri]): FilterBySchema =
    registry
      .lookupA[FilterBySchema.type](FilterBySchema.ref)
      .rightValue
      .withJsonLdConfig(FilterBySchemaConfig(ViewRestriction.fromSet(schemas)).toJsonLd)
      .rightValue

  test("Do not filter elements if the expected schema set is empty") {
    val elem = element(Latest(iri"http://localhost/schema1"))
    pipe(Set.empty).apply(elem).assertEquals(elem)
  }

  test("Do not filter elements if the schema intersection is not void") {
    val elem = element(Revision(iri"http://localhost/schema1", 2))
    pipe(Set(iri"http://localhost/schema1", iri"http://localhost/schema2")).apply(elem).assertEquals(elem)
  }

  test("Filter elements if the type intersection is void") {
    val elem = element(Revision(iri"http://localhost/schema1", 2))
    pipe(Set(iri"http://localhost/schema2", iri"http://localhost/schema3")).apply(elem).assertEquals(elem.dropped)
  }
}
