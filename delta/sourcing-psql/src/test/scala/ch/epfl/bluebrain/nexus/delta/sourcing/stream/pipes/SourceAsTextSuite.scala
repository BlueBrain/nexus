package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ElemCtx, ReferenceRegistry}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import io.circe.Json

import java.time.Instant

class SourceAsTextSuite extends BioSuite {

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
  registry.register(SourceAsText)

  test("Embed the source to the metadata graph") {
    val elem             = SuccessElem(
      ElemCtx.SourceId(base),
      tpe = PullRequest.entityType,
      id = base / "id",
      rev = 1,
      instant = instant,
      offset = Offset.at(1L),
      value = uniformState
    )
    val newMetadataGraph = uniformState.metadataGraph.add(nxv.originalSource.iri, uniformState.source.noSpaces)
    val expected         = elem.copy(value = uniformState.copy(metadataGraph = newMetadataGraph, source = Json.obj()))

    val pipe = registry
      .lookupA[SourceAsText.type](SourceAsText.reference)
      .rightValue
      .withJsonLdConfig(ExpandedJsonLd.empty)
      .rightValue
    pipe(elem).assert(expected)
  }
}
