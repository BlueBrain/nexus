package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.Task

import java.util.UUID

class CompositeViewsCoordinatorSuite extends BioSuite with CompositeViewsFixture {

  private val existingViewRef                    = ViewRef(projectRef, nxv + "my-view")
  private val anotherView                        = ViewRef(projectRef, nxv + "another-view")
  private def activeView(ref: ViewRef, rev: Int) = ActiveViewDef(
    ref,
    UUID.randomUUID(),
    rev,
    viewValue
  )

  private def deprecatedView(ref: ViewRef) = DeprecatedViewDef(ref)

  // Apply clean up and return the affected view if any
  private def cleanup(newView: CompositeViewDef, cachedViews: List[ActiveViewDef]) = {
    for {
      // Creates the cache
      cache        <- KeyValueStore[ViewRef, ActiveViewDef]().tapEval { c =>
                        cachedViews.traverse { v => c.put(v.ref, v) }
                      }
      // The destroy action
      destroyed    <- Ref.of[Task, Option[ActiveViewDef]](None)
      destroy       = (active: ActiveViewDef) => destroyed.set(Some(active))
      _            <- CompositeViewsCoordinator.cleanupCurrent(cache, newView, destroy)
      destroyedOpt <- destroyed.get
    } yield destroyedOpt

  }

  test("Do not clean up for the same view has not changed") {
    val existingView = activeView(existingViewRef, 1)
    cleanup(existingView, List(existingView)).assertNone
  }

  test("Do not clean up for a new view") {
    val existingView = activeView(existingViewRef, 1)
    val another      = activeView(anotherView, 3)
    cleanup(another, List(existingView)).assertNone
  }

  test("Do not clean up for a new deprecated view") {
    val existingView = activeView(existingViewRef, 1)
    val another      = deprecatedView(anotherView)
    cleanup(another, List(existingView)).assertNone
  }

  test("Clean up if the view gets deprecated") {
    val existingView = activeView(existingViewRef, 1)
    val deprecated   = deprecatedView(existingViewRef)
    cleanup(deprecated, List(existingView)).assertSome(existingView)
  }

  test("Clean up if the view is updated") {
    val existingView = activeView(existingViewRef, 1)
    val updatedView  = activeView(existingViewRef, 2)
    cleanup(updatedView, List(existingView)).assertSome(existingView)
  }

}
