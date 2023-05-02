package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import fs2.Stream
import monix.bio.Task

import java.util.UUID

class CompositeViewsDeletionTaskSuite extends BioSuite with CirceLiteral with CompositeViewsFixture {

  implicit private val anonymous: Subject = Anonymous

  private val rev = 2

  private val active1    = ViewRef(projectRef, nxv + "active1")
  private val active2    = ViewRef(projectRef, nxv + "active2")
  private val deprecated = ViewRef(projectRef, nxv + "deprecated")

  private def activeView(ref: ViewRef) = ActiveViewDef(
    ref,
    UUID.randomUUID(),
    rev,
    viewValue
  )

  private val viewStream: Stream[Task, CompositeViewDef] =
    Stream(
      activeView(active1),
      DeprecatedViewDef(deprecated),
      activeView(active2)
    )

  test("Deprecate all active views for project") {
    for {
      deprecated   <- Ref.of[Task, Set[ViewRef]](Set.empty)
      deprecateView = (view: ActiveViewDef) => deprecated.getAndUpdate(_ + view.ref).void.hideErrors
      deletionTask  = new CompositeViewsDeletionTask(_ => viewStream, (view, _) => deprecateView(view))
      result       <- deletionTask(projectRef)
      _             = assertEquals(result.log.size, 2, s"'$active1' and '$active2' should appear in the result:\n$result")
      _             = deprecated.get.assert(Set(active1, active2))
    } yield ()
  }

}
