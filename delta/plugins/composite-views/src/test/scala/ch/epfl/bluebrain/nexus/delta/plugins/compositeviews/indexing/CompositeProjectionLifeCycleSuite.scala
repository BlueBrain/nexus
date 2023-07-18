package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycle.Hook
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStatus, ExecutionStrategy, Projection, ProjectionMetadata}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import monix.bio.{Task, UIO}
import munit.Location

import concurrent.duration._
import java.util.UUID

class CompositeProjectionLifeCycleSuite extends BioSuite with CompositeViewsFixture {

  implicit private val batch: BatchConfig             = BatchConfig(2, 10.millis)
  implicit private val patienceConfig: PatienceConfig = PatienceConfig(500.millis, 10.millis)

  private val firstHookName  = "first-hook"
  private val secondHookName = "second-hook"

  private val noHookView    = nxv + "no-hook"
  private val firstHookView = nxv + "first-hook"
  private val allHooksView  = nxv + "all-hooks"

  private def createHook(name: String, test: ViewRef => Boolean, ref: Ref[Task, Set[String]]): Hook =
    (view: CompositeViewDef.ActiveViewDef) => {
      Option.when(test(view.ref)) {
        ref.update {
          _ + name
        }
      }
    }
  private def assertView(view: ActiveViewDef, hooks: Set[String])(implicit loc: Location)           =
    for {
      triggeredHooks <- Ref.of[Task, Set[String]](Set.empty)
      indexing       <- Ref.of[Task, Option[ViewRef]](None)
      firstHook       = createHook(firstHookName, _.viewId != noHookView, triggeredHooks)
      secondHook      = createHook(secondHookName, _.viewId == allHooksView, triggeredHooks)
      lifecycle       = CompositeProjectionLifeCycle(
                          Set(firstHook, secondHook),
                          _ => Task.unit,
                          view => {
                            val metadata   = ProjectionMetadata("test", "lifecycle")
                            val projection =
                              CompiledProjection.fromTask(metadata, ExecutionStrategy.TransientSingleNode, Task.unit)
                            indexing.update { _ => Some(view.ref) }.as(projection)
                          },
                          _ => Task.unit
                        )
      compiled       <- lifecycle.build(view)
      projection     <- Projection(compiled, UIO.none, _ => UIO.unit, _ => UIO.unit)
      _              <- projection.executionStatus.eventually(ExecutionStatus.Completed)
      // Asserting hooks
      _              <- triggeredHooks.get.eventually(hooks)
      // If no hook have been provided then we expect to fall back on indexing
      _              <- indexing.get.assert(Option.when(hooks.isEmpty)(view.ref))
    } yield ()

  test("Fall back to indexing when no hook is matched by the view") {
    val view = ActiveViewDef(ViewRef(project.ref, noHookView), UUID.randomUUID(), 1, viewValue)
    assertView(view, Set.empty)
  }

  test("Trigger the first hook when it is matched by the view and skip indexing") {
    val view = ActiveViewDef(ViewRef(project.ref, firstHookView), UUID.randomUUID(), 1, viewValue)
    assertView(view, Set(firstHookName))
  }

  test("Trigger all matching hooks and skip indexing") {
    val view = ActiveViewDef(ViewRef(project.ref, allHooksView), UUID.randomUUID(), 1, viewValue)
    assertView(view, Set(firstHookName, secondHookName))
  }
}
