package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewState, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, Fixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, PipeStep, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotFindTypedPipeErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{FilterByType, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import io.circe.Json
import monix.bio.UIO

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class IndexingViewDefSuite extends BioSuite with CirceLiteral with Fixtures {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(500.millis, 10.millis)

  implicit private val batch: BatchConfig = BatchConfig(2, 10.millis)

  private val defaultMapping  = jobj"""{"defaultMapping": {}}"""
  private val defaultSettings = jobj"""{"defaultSettings": {}}"""
  private val prefix          = "prefix"

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val id               = nxv + "indexing-view"
  private val viewRef          = ViewRef(projectRef, id)
  private val subject: Subject = Anonymous
  private val tag              = UserTag.unsafe("mytag")

  private val customMapping      = jobj"""{"properties": {}}"""
  private val customSettings     = jobj"""{"analysis": {}}"""
  private val filterByTypeConfig = FilterByTypeConfig(Set(nxv + "PullRequest"))
  private val indexingCustom     = IndexingElasticSearchViewValue(
    Some("viewName"),
    Some("viewDescription"),
    Some(UserTag.unsafe("some.tag")),
    List(
      PipeStep(FilterByType.ref.label, filterByTypeConfig.toJsonLd),
      PipeStep.noConfig(FilterDeprecated.ref)
    ),
    Some(customMapping),
    Some(customSettings),
    context = Some(ContextObject(jobj"""{"@vocab": "http://schema.org/"}""")),
    Permission.unsafe("my/permission")
  )

  private val indexingDefault = IndexingElasticSearchViewValue(
    None,
    List.empty,
    None,
    None,
    context = None,
    Permission.unsafe("my/permission")
  )

  private val aggregate = AggregateElasticSearchViewValue(NonEmptySet.of(viewRef))
  private val sink      = CacheSink.states[Json]

  private val indexingRev = IndexingRev.init
  private val rev         = 2

  private def state(v: ElasticSearchViewValue) = ElasticSearchViewState(
    id,
    projectRef,
    uuid,
    v,
    Json.obj("elastic" -> Json.fromString("value")),
    Tags(tag           -> 3),
    rev = rev,
    indexingRev = indexingRev,
    deprecated = false,
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  test("Build an active view def with a custom mapping and settings") {
    assertEquals(
      IndexingViewDef(state(indexingCustom), defaultMapping, defaultSettings, prefix),
      Some(
        ActiveViewDef(
          viewRef,
          s"elasticsearch-$projectRef-$id-${indexingRev.value}",
          indexingCustom.pipeChain,
          indexingCustom.selectFilter,
          IndexLabel.fromView("prefix", uuid, indexingRev),
          customMapping,
          customSettings,
          indexingCustom.context,
          indexingRev,
          rev
        )
      )
    )
  }

  test("Build an active view def with no mapping and settings defined") {
    assertEquals(
      IndexingViewDef(state(indexingDefault), defaultMapping, defaultSettings, prefix),
      Some(
        ActiveViewDef(
          viewRef,
          s"elasticsearch-$projectRef-$id-${indexingRev.value}",
          indexingDefault.pipeChain,
          indexingDefault.selectFilter,
          IndexLabel.fromView("prefix", uuid, indexingRev),
          defaultMapping,
          defaultSettings,
          indexingDefault.context,
          indexingRev,
          rev
        )
      )
    )
  }

  test("Build an deprecated view def") {
    assertEquals(
      IndexingViewDef(state(indexingDefault).copy(deprecated = true), defaultMapping, defaultSettings, prefix),
      Some(
        DeprecatedViewDef(
          viewRef
        )
      )
    )
  }

  test("Ignore aggregate views") {
    assertEquals(
      IndexingViewDef(state(aggregate), defaultMapping, defaultSettings, prefix),
      None
    )
  }

  test("Fail if the pipe chain does not compile") {
    val v = ActiveViewDef(
      viewRef,
      s"elasticsearch-$projectRef-$id-$indexingRev",
      Some(PipeChain(PipeRef.unsafe("xxx") -> ExpandedJsonLd.empty)),
      indexingDefault.selectFilter,
      IndexLabel.fromView("prefix", uuid, indexingRev),
      defaultMapping,
      defaultSettings,
      indexingDefault.context,
      indexingRev,
      rev
    )

    val expectedError = CouldNotFindTypedPipeErr(PipeRef.unsafe("xxx"), "xxx")

    IndexingViewDef
      .compile(
        v,
        _ => Left(expectedError),
        GraphResourceStream.empty,
        sink
      )
      .error(expectedError)

    assert(
      sink.successes.isEmpty && sink.dropped.isEmpty && sink.failed.isEmpty,
      "No elem should have been processed"
    )

  }

  test("Success and be able to process the different elements") {
    val v = ActiveViewDef(
      viewRef,
      s"elasticsearch-$projectRef-$id-$indexingRev",
      Some(PipeChain(FilterDeprecated())),
      indexingDefault.selectFilter,
      IndexLabel.fromView("prefix", uuid, indexingRev),
      defaultMapping,
      defaultSettings,
      indexingDefault.context,
      indexingRev,
      rev
    )

    val expectedProgress: ProjectionProgress = ProjectionProgress(
      Offset.at(4L),
      Instant.EPOCH,
      processed = 4,
      discarded = 2,
      failed = 1
    )

    for {
      compiled   <- IndexingViewDef.compile(
                      v,
                      _ => Operation.merge(FilterDeprecated.withConfig(()), FilterByType.withConfig(filterByTypeConfig)),
                      GraphResourceStream.unsafeFromStream(PullRequestStream.generate(projectRef)),
                      sink
                    )
      _           = assertEquals(
                      compiled.metadata,
                      ProjectionMetadata(ElasticSearchViews.entityType.value, v.projection, Some(projectRef), Some(id))
                    )
      projection <- Projection(compiled, UIO.none, _ => UIO.unit, _ => UIO.unit)
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _          <- projection.currentProgress.assert(expectedProgress)
    } yield ()
  }

}
