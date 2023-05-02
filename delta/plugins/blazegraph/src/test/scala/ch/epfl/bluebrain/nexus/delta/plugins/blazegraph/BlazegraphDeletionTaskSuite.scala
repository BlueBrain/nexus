package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import fs2.Stream
import monix.bio.Task

class BlazegraphDeletionTaskSuite extends BioSuite {

  implicit private val subject: Subject = Anonymous

  private val project     = ProjectRef.unsafe("org", "proj")
  private val indexingRev = 1
  private val rev         = 2

  private val active1    = ViewRef(project, nxv + "active1")
  private val active2    = ViewRef(project, nxv + "active2")
  private val deprecated = ViewRef(project, nxv + "deprecated")

  private def activeView(ref: ViewRef) = ActiveViewDef(
    ref,
    projection = ref.viewId.toString,
    None,
    None,
    namespace = "view2",
    indexingRev,
    rev
  )

  private val viewStream: Stream[Task, IndexingViewDef] =
    Stream(
      activeView(active1),
      DeprecatedViewDef(deprecated),
      activeView(active2)
    )

  test("Deprecate all active views for project") {
    for {
      deprecated   <- Ref.of[Task, Set[ViewRef]](Set.empty)
      deprecateView = (view: ActiveViewDef) => deprecated.getAndUpdate(_ + view.ref).void.hideErrors
      deletionTask  = new BlazegraphDeletionTask(_ => viewStream, (view, _) => deprecateView(view))
      result       <- deletionTask(project)
      _             = assertEquals(result.log.size, 2, s"'$active1' and '$active2' should appear in the result:\n$result")
      _             = deprecated.get.assert(Set(active1, active2))
    } yield ()
  }

}
