package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterBySchema.FilterBySchemaConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ElemCtx, ReferenceRegistry}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite

import java.time.Instant

class FilterBySchemaSuite extends BioSuite {

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
  private val uniformState = PullRequestState.uniformScopedStateEncoder(base).toUniformScopedState(state)

  private val registry = new ReferenceRegistry
  registry.register(FilterBySchema)

  def element(schema: ResourceRef): SuccessElem[UniformScopedState] =
    SuccessElem(
      ElemCtx.SourceId(base),
      tpe = PullRequest.entityType,
      id = base / "id",
      rev = 1,
      instant = instant,
      offset = Offset.at(1L),
      value = uniformState.copy(schema = schema)
    )

  def pipe(schemas: Set[Iri]): FilterBySchema =
    registry
      .lookupA[FilterBySchema.type](FilterBySchema.reference)
      .rightValue
      .withJsonLdConfig(FilterBySchemaConfig(schemas).toJsonLd)
      .rightValue

  test("Do not filter elements if the expected schema set is empty") {
    val elem = element(Latest(iri"http://localhost/schema1"))
    pipe(Set.empty).apply(elem).assert(elem)
  }

  test("Do not filter elements if the schema intersection is not void") {
    val elem = element(Revision(iri"http://localhost/schema1", 2L))
    pipe(Set(iri"http://localhost/schema1", iri"http://localhost/schema2")).apply(elem).assert(elem)
  }

  test("Filter elements if the type intersection is void") {
    val elem = element(Revision(iri"http://localhost/schema1", 2L))
    pipe(Set(iri"http://localhost/schema2", iri"http://localhost/schema3")).apply(elem).assert(elem.dropped)
  }
}
