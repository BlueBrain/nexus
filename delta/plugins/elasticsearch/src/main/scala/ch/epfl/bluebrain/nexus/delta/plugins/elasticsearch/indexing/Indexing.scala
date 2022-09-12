package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.data.{Chain, NonEmptyChain}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{index, projectionName}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexToElasticSearch.IndexToElasticSearchConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.UniformScopedStateToDocument.UniformScopedStateToDocumentConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.ScopedStateSource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.ScopedStateSource.StateSourceConfig
import monix.bio.Task

import java.util.UUID

object Indexing {

  def startIndexing(registry: ReferenceRegistry, supervisor: Supervisor): Task[Unit] = {
    val compiled = ProjectionDef(
      name = "indexing-elasticsearch-views",
      project = None,
      resourceId = None,
      sources = NonEmptyChain(
        SourceChain(
          source = SourceRef(Label.unsafe("elasticsearch-view-states")),
          sourceId = nxv + "elasticsearch-view-states",
          sourceConfig = ExpandedJsonLd.empty,
          pipes = Chain()
        )
      ),
      pipes = NonEmptyChain(
        PipeChain(
          id = nxv + "elasticsearch-view-states",
          pipes = NonEmptyChain(
            PipeRef(ConfigureEsIndexingViews.label) -> ExpandedJsonLd.empty
          )
        )
      )
    ).compile(registry)
    Task.fromEither(compiled).flatMap(_.supervise(supervisor, ExecutionStrategy.EveryNode))
  }

  def projectionDefFor(
      value: IndexingElasticSearchViewValue,
      state: ElasticSearchViewState,
      prefix: String
  ): ProjectionDef =
    projectionDefFor(value, state.project, state.id, state.rev, state.uuid, prefix)

  def projectionDefFor(
      value: IndexingElasticSearchViewValue,
      project: ProjectRef,
      id: Iri,
      rev: Int,
      uuid: UUID,
      prefix: String
  ): ProjectionDef = {
    val source = SourceChain(
      SourceRef(ScopedStateSource.label),
      id,
      StateSourceConfig(project, value.resourceTag.getOrElse(Latest)).toJsonLd,
      Chain
        .fromSeq(value.pipeline.map(step => (PipeRef(step.name), step.config.getOrElse(ExpandedJsonLd.empty))))
    )
    val pipes  = PipeChain(
      id,
      NonEmptyChain(
        PipeRef(UniformScopedStateToDocument.label) -> UniformScopedStateToDocumentConfig(value.context).toJsonLd,
        PipeRef(IndexToElasticSearch.label)         -> IndexToElasticSearchConfig(index(uuid, rev, prefix)).toJsonLd
      )
    )
    ProjectionDef(
      projectionName(project, id, rev),
      Some(project),
      Some(id),
      NonEmptyChain(source),
      NonEmptyChain(pipes)
    )
  }

}
