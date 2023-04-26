package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import munit.FunSuite

class CompositeViewFieldsSuite extends FunSuite with CompositeViewsFixture {

  test("Projecting the ViewValue to ViewFields should be correct") {
    assertEquals(CompositeViewFields.fromValue(viewValue), viewFields)
  }

}
