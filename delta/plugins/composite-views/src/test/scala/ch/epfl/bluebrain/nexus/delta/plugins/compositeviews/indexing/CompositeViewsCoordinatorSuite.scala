package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
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

  // Apply clean up and return the affected view if any
  private def cleanup(newView: CompositeViewDef, cachedViews: List[ActiveViewDef]) = {
    for {
      // Creates the cache
      cache        <- KeyValueStore[ViewRef, ActiveViewDef]().tapEval { c =>
                        cachedViews.traverse { v => c.put(v.ref, v) }
                      }
      // The destroy action
      destroyed    <- Ref.of[Task, Option[ActiveViewDef]](None)
      destroy       = (active: ActiveViewDef, _: CompositeViewDef) =>
                        destroyed.getAndUpdate {
                          case Some(current) =>
                            throw new IllegalArgumentException(s"Destroy has already been called on $current")
                          case None          => Some(active)
                        }.void
      _            <- CompositeViewsCoordinator.cleanupCurrent(cache, newView, destroy)
      destroyedOpt <- destroyed.get
    } yield destroyedOpt

  }

  test("Do not trigger clean up if the the view is not running yet") {
    val existingView = activeView(existingViewRef, 1)
    cleanup(existingView, List.empty).assertNone
  }

  test("Trigger clean up if the view is running") {
    val existingView = activeView(existingViewRef, 1)
    cleanup(existingView, List(existingView)).assertSome(existingView)
  }

  test("Do not trigger clean up for a new view") {
    val existingView = activeView(existingViewRef, 1)
    val another      = activeView(anotherView, 3)
    cleanup(another, List(existingView)).assertNone
  }

}
