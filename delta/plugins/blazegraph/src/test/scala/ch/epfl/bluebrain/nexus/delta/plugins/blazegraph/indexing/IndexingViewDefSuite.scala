package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.data.NonEmptySet
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewState, BlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotFindTypedPipeErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{FilterByType, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import io.circe.Json

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class IndexingViewDefSuite extends NexusSuite {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(500.millis, 10.millis)

  implicit private val batch: BatchConfig = BatchConfig(2, 10.millis)

  private val prefix = "prefix"

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val id               = nxv + "indexing-view"
  private val viewRef          = ViewRef(projectRef, id)
  private val subject: Subject = Anonymous
  private val tag              = UserTag.unsafe("mytag")
  private val indexingRev      = 1
  private val currentRev       = 1

  private val namespace = s"${prefix}_${uuid}_1"

  private val filterByTypeConfig = FilterByTypeConfig(IriFilter.restrictedTo(nxv + "PullRequest"))
  private val indexing           = IndexingBlazegraphViewValue(
    resourceTag = Some(UserTag.unsafe("some.tag")),
    resourceTypes = IriFilter.restrictedTo(nxv + "PullRequest")
  )

  private val aggregate =
    AggregateBlazegraphViewValue(Some("viewName"), Some("viewDescription"), NonEmptySet.of(viewRef))
  private val sink      = CacheSink.states[NTriples]

  private def state(v: BlazegraphViewValue) = BlazegraphViewState(
    id,
    projectRef,
    uuid,
    v,
    Json.obj("blazegraph" -> Json.fromString("value")),
    Tags(tag              -> 3),
    rev = 1,
    indexingRev = indexingRev,
    deprecated = false,
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  test("Build an active view def") {
    assertEquals(
      IndexingViewDef(state(indexing), prefix),
      Some(
        ActiveViewDef(
          viewRef,
          s"blazegraph-$projectRef-$id-$indexingRev",
          indexing.selectFilter,
          indexing.pipeChain,
          namespace,
          indexingRev,
          currentRev
        )
      )
    )
  }

  test("Build an deprecated view def") {
    assertEquals(
      IndexingViewDef(state(indexing).copy(deprecated = true), prefix),
      Some(DeprecatedViewDef(viewRef))
    )
  }

  test("Ignore aggregate views") {
    assertEquals(
      IndexingViewDef(state(aggregate), prefix),
      None
    )
  }

  test("Fail if the pipe chain does not compile") {
    val v = ActiveViewDef(
      viewRef,
      s"blazegraph-$projectRef-$id-1",
      indexing.selectFilter,
      Some(PipeChain(PipeRef.unsafe("xxx") -> ExpandedJsonLd.empty)),
      namespace,
      indexingRev,
      currentRev
    )

    val expectedError = CouldNotFindTypedPipeErr(PipeRef.unsafe("xxx"), "xxx")

    IndexingViewDef
      .compile(
        v,
        _ => Left(expectedError),
        GraphResourceStream.empty,
        sink
      )
      .interceptEquals(expectedError)

    assert(
      sink.successes.isEmpty && sink.dropped.isEmpty && sink.failed.isEmpty,
      "No elem should have been processed"
    )

  }

  test("Success and be able to process the different elements") {
    val v = ActiveViewDef(
      viewRef,
      s"blazegraph-$projectRef-$id-1",
      indexing.selectFilter,
      Some(PipeChain(FilterDeprecated())),
      namespace,
      indexingRev,
      currentRev
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
                      ProjectionMetadata(BlazegraphViews.entityType.value, v.projection, Some(projectRef), Some(id))
                    )
      projection <- Projection(compiled, IO.none, _ => IO.unit, _ => IO.unit)
      _          <- projection.executionStatus.assertEquals(ExecutionStatus.Completed).eventually
      _          <- projection.currentProgress.assertEquals(expectedProgress)
    } yield ()
  }

}
