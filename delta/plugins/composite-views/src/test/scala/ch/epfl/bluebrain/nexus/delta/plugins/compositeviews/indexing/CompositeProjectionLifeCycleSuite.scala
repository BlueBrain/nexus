package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.data.NonEmptyMapImpl
import cats.effect.IO
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycle.Hook
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycleSuite.DestroyResult
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycleSuite.DestroyResult._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, IndexingViewRef, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsEffectSuite, PatienceConfig}
import munit.Location

import java.util.UUID
import scala.concurrent.duration._

class CompositeProjectionLifeCycleSuite extends CatsEffectSuite with CompositeViewsFixture {

  implicit private val batch: BatchConfig             = BatchConfig(2, 10.millis)
  implicit private val patienceConfig: PatienceConfig = PatienceConfig(500.millis, 10.millis)

  private val firstHookName  = "first-hook"
  private val secondHookName = "second-hook"

  private val noHookView    = nxv + "no-hook"
  private val firstHookView = nxv + "first-hook"
  private val allHooksView  = nxv + "all-hooks"

  private def createHook(name: String, test: ViewRef => Boolean, ref: Ref[IO, Set[String]]): Hook =
    (view: CompositeViewDef.ActiveViewDef) => {
      Option.when(test(view.ref)) {
        ref.update {
          _ + name
        }
      }
    }
  private def assertViewHooks(view: ActiveViewDef, hooks: Set[String])(implicit loc: Location)    =
    for {
      triggeredHooks <- Ref.of[IO, Set[String]](Set.empty)
      indexing       <- Ref.of[IO, Option[ViewRef]](None)
      firstHook       = createHook(firstHookName, _.viewId != noHookView, triggeredHooks)
      secondHook      = createHook(secondHookName, _.viewId == allHooksView, triggeredHooks)
      lifecycle       = CompositeProjectionLifeCycle(
                          Set(firstHook, secondHook),
                          _ => IO.unit,
                          view => {
                            val metadata   = ProjectionMetadata("test", "lifecycle")
                            val projection =
                              CompiledProjection.fromTask(metadata, ExecutionStrategy.TransientSingleNode, IO.unit)
                            indexing.update { _ => Some(view.ref) }.as(projection)
                          },
                          _ => IO.unit,
                          (_, _) => IO.unit
                        )
      compiled       <- lifecycle.build(view)
      projection     <- Projection(compiled, IO.none, _ => IO.unit, _ => IO.unit)
      _              <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      // Asserting hooks
      _              <- triggeredHooks.get.eventually(hooks)
      // If no hook have been provided then we expect to fall back on indexing
      _              <- indexing.get.assertEquals(Option.when(hooks.isEmpty)(view.ref))
    } yield ()

  test("Fall back to indexing when no hook is matched by the view") {
    val view = ActiveViewDef(ViewRef(project.ref, noHookView), UUID.randomUUID(), 1, viewValue)
    assertViewHooks(view, Set.empty)
  }

  test("Trigger the first hook when it is matched by the view and skip indexing") {
    val view = ActiveViewDef(ViewRef(project.ref, firstHookView), UUID.randomUUID(), 1, viewValue)
    assertViewHooks(view, Set(firstHookName))
  }

  test("Trigger all matching hooks and skip indexing") {
    val view = ActiveViewDef(ViewRef(project.ref, allHooksView), UUID.randomUUID(), 1, viewValue)
    assertViewHooks(view, Set(firstHookName, secondHookName))
  }

  private def destroyOnChange(prev: ActiveViewDef, next: CompositeViewDef) =
    for {
      ref      <- Ref.of[IO, DestroyResult](DestroyResult.skipped)
      lifecycle = CompositeProjectionLifeCycle(
                    Set.empty,
                    _ => IO.unit,
                    _ => IO.raiseError(new IllegalStateException("Index operation should not be called")),
                    view => ref.set(DestroyAll(view.indexingRef)),
                    (view, projection) =>
                      ref.update {
                        case DestroyProjection(ref, projections) => DestroyProjection(ref, projections + projection.id)
                        case _                                   => DestroyProjection(view.indexingRef, Set(projection.id))
                      }
                  )
      _        <- lifecycle.destroyOnIndexingChange(prev, next)
      result   <- ref.get
    } yield result

  private val existingViewRef = ViewRef(projectRef, nxv + "my-view")
  private val anotherView     = ViewRef(projectRef, nxv + "another-view")

  private def activeView(ref: ViewRef, rev: Int) = ActiveViewDef(
    ref,
    UUID.randomUUID(),
    rev,
    viewValue
  )

  private def activeView(ref: ViewRef, rev: Int, sourceIndexingRev: IndexingRev) = ActiveViewDef(
    ref,
    UUID.randomUUID(),
    rev,
    viewValue.copy(sourceIndexingRev = sourceIndexingRev)
  )

  private def activeView(ref: ViewRef, rev: Int, projectionIndexingRevs: (Iri, IndexingRev)*) = {
    val asMap              = projectionIndexingRevs.toMap
    val updatedProjections = viewValue.projections.map {
      case p if asMap.contains(p.id) => p.updateIndexingRev(asMap(p.id))
      case p                         => p
    }
    ActiveViewDef(
      ref,
      UUID.randomUUID(),
      rev,
      viewValue.copy(projections = updatedProjections)
    )
  }

  private def activeView(ref: ViewRef, rev: Int, projectionsToKeep: Set[Iri]) = {
    val updatedProjections = NonEmptyMapImpl.fromMapUnsafe(viewValue.projections.filter { p =>
      projectionsToKeep.contains(p.id)
    })
    ActiveViewDef(
      ref,
      UUID.randomUUID(),
      rev,
      viewValue.copy(projections = updatedProjections)
    )
  }

  private def deprecatedView(ref: ViewRef) = DeprecatedViewDef(ref)

  test("Raise an error when providing two different views") {
    val previous = activeView(existingViewRef, 1)
    val next     = activeView(anotherView, 2)

    destroyOnChange(previous, next).intercept[IllegalArgumentException]
  }

  test("Destroy fully a view that has been deprecated") {
    val previous = activeView(existingViewRef, 1)
    val next     = deprecatedView(existingViewRef)
    destroyOnChange(previous, next).assertEquals(DestroyAll(previous.indexingRef))
  }

  test("Destroy fully a view when sources have been updated") {
    val previous = activeView(existingViewRef, 3, IndexingRev(2))
    val next     = activeView(existingViewRef, 4, IndexingRev(4))
    destroyOnChange(previous, next).assertEquals(DestroyAll(previous.indexingRef))
  }

  test("Destroy partially a view when a projection has been updated") {
    val previous =
      activeView(existingViewRef, 3, esProjection.id -> IndexingRev(2), blazegraphProjection.id -> IndexingRev(1))
    val next     =
      activeView(existingViewRef, 4, esProjection.id -> IndexingRev(2), blazegraphProjection.id -> IndexingRev(4))
    destroyOnChange(previous, next).assertEquals(DestroyProjection(previous.indexingRef, Set(blazegraphProjection.id)))
  }

  test("Destroy partially a view when several projections have been updated") {
    val previous =
      activeView(existingViewRef, 3, esProjection.id -> IndexingRev(2), blazegraphProjection.id -> IndexingRev(1))
    val next     =
      activeView(existingViewRef, 4, esProjection.id -> IndexingRev(4), blazegraphProjection.id -> IndexingRev(4))
    destroyOnChange(previous, next).assertEquals(
      DestroyProjection(previous.indexingRef, Set(esProjection.id, blazegraphProjection.id))
    )
  }

  test("Destroy partially a view when a projection is removed") {
    val previous = activeView(existingViewRef, 3)
    val next     = activeView(existingViewRef, 4, Set(esProjection.id))
    destroyOnChange(previous, next).assertEquals(DestroyProjection(previous.indexingRef, Set(blazegraphProjection.id)))
  }

  test("Do not perform any destroy operation on a view when no indexing configuration has been updated") {
    val previous = activeView(existingViewRef, 3)
    val next     = activeView(existingViewRef, 4)
    destroyOnChange(previous, next).assertEquals(Skipped)
  }
}

object CompositeProjectionLifeCycleSuite {

  sealed trait DestroyResult

  object DestroyResult {

    val skipped: DestroyResult = Skipped

    final case object Skipped extends DestroyResult

    final case class DestroyAll(view: IndexingViewRef)                               extends DestroyResult
    final case class DestroyProjection(view: IndexingViewRef, projections: Set[Iri]) extends DestroyResult

  }

}
