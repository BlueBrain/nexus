package ch.epfl.bluebrain.nexus.delta.sdk.views

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ViewRefVisitorSpec extends AnyWordSpecLike with Matchers with IOValues {

  private val project = ProjectRef.unsafe("org", "proj")
  private val one       = IndexedVisitedView(ViewRef(project, nxv + "a"), permissions.read, "a")
  private val two       = AggregatedVisitedView(
    ViewRef(project, nxv + "b"),
    NonEmptySet.of(ViewRef(project, nxv + "c"), ViewRef(project, nxv + "d"))
  )
  private val three       = AggregatedVisitedView(
    ViewRef(project, nxv + "c"),
    NonEmptySet.of(ViewRef(project, nxv + "b"), ViewRef(project, nxv + "d"))
  )
  private val four       = IndexedVisitedView(ViewRef(project, nxv + "d"), permissions.read, "d")

  private def fetchView(iri: Iri, projectRef: ProjectRef): IO[Unit, VisitedView] =
    if (iri == nxv + "a" && project == projectRef) IO.pure(one)
    else if (iri == nxv + "b" && project == projectRef) IO.pure(two)
    else if (iri == nxv + "c" && project == projectRef) IO.pure(three)
    else if (iri == nxv + "d" && project == projectRef) IO.pure(four)
    else IO.raiseError(())

  "A ViewRefVisitor" should {
    val visitor = new ViewRefVisitor(fetchView)

    "visit all references" in {
      visitor.visitAll(NonEmptySet.of(ViewRef(project, nxv + "b"))).accepted shouldEqual Set(two, three, four)

      visitor.visitAll(NonEmptySet.of(ViewRef(project, nxv + "b"), ViewRef(project, nxv + "a"))).accepted shouldEqual
        Set(one, two, three, four)

      visitor.visitAll(NonEmptySet.of(ViewRef(project, nxv + "c"))).accepted shouldEqual Set(two, three, four)

      visitor.visitAll(NonEmptySet.of(ViewRef(project, nxv + "a"))).accepted shouldEqual Set(one)
    }
  }

}
