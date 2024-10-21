package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.supervision

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream

import java.util.UUID

class CompositeViewsByNamespaceSuite extends NexusSuite with CompositeViewsFixture {

  test("Get the different views by their common namespace value") {
    val rev     = 2
    val project = ProjectRef.unsafe("org", "proj")

    def activeView(suffix: String) = {
      val id = nxv + suffix
      ActiveViewDef(
        ViewRef(project, id),
        UUID.randomUUID(),
        rev,
        viewValue
      )
    }

    val view1 = activeView("view1")
    val view2 = activeView("view2")

    val id3            = nxv + "view3"
    val deprecatedView = DeprecatedViewDef(ViewRef(project, id3))

    val stream = Stream(view1, view2, deprecatedView)

    val view1Namespace = s"test_${view1.uuid}_${view1.indexingRev.value}"
    val view2Namespace = s"test_${view2.uuid}_${view2.indexingRev.value}"

    val expected = Map(view1Namespace -> view1.ref, view2Namespace -> view2.ref)
    CompositeViewsByNamespace(stream, "test").get.assertEquals(expected)

  }

}
