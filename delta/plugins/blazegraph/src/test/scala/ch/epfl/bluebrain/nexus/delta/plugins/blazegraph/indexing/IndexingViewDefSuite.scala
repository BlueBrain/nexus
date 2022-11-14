package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewState, BlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.CouldNotFindTypedPipeErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterByType.FilterByTypeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{FilterByType, FilterDeprecated}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import fs2.Stream
import io.circe.Json
import monix.bio.UIO

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class IndexingViewDefSuite extends BioSuite {

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

  private val namespace = s"${prefix}_${uuid}_1"

  private val filterByTypeConfig = FilterByTypeConfig(Set(nxv + "PullRequest"))
  private val indexing           = IndexingBlazegraphViewValue(
    resourceTag = Some(UserTag.unsafe("some.tag")),
    resourceTypes = Set(nxv + "PullRequest")
  )

  private val aggregate =
    AggregateBlazegraphViewValue(Some("viewName"), Some("viewDescription"), NonEmptySet.of(viewRef))
  private val sink      = new CacheSink[NTriples]()

  private def state(v: BlazegraphViewValue) = BlazegraphViewState(
    id,
    projectRef,
    uuid,
    v,
    Json.obj("blazegraph" -> Json.fromString("value")),
    Tags(tag              -> 3),
    rev = 1,
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
          s"blazegraph-$projectRef-$id-1",
          indexing.resourceTag,
          indexing.pipeChain,
          namespace
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
      indexing.resourceTag,
      Some(PipeChain(PipeRef.unsafe("xxx") -> ExpandedJsonLd.empty)),
      namespace
    )

    val expectedError = CouldNotFindTypedPipeErr(PipeRef.unsafe("xxx"), "xxx")

    IndexingViewDef
      .compile(
        v,
        _ => Left(expectedError),
        (_: ProjectRef, _: Tag, _: Offset) => Stream.empty,
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
      s"blazegraph-$projectRef-$id-1",
      indexing.resourceTag,
      Some(PipeChain(PipeRef(FilterDeprecated.label) -> ExpandedJsonLd.empty)),
      namespace
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
                      (_: ProjectRef, _: Tag, _: Offset) => PullRequestStream.generate(projectRef),
                      sink
                    )
      _           = assertEquals(
                      compiled.metadata,
                      ProjectionMetadata(BlazegraphViews.entityType.value, v.projection, Some(projectRef), Some(id))
                    )
      projection <- Projection(compiled, UIO.none, _ => UIO.unit, _ => UIO.unit)
      _          <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      _          <- projection.currentProgress.assert(expectedProgress)
    } yield ()
  }

}
