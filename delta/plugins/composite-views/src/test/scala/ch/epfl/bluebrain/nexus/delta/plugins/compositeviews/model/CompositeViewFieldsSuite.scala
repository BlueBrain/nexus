package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import munit.FunSuite

class CompositeViewFieldsSuite extends FunSuite with CompositeViewsFixture {

  test("Projecting the ViewValue to ViewFields should be correct") {
    assertEquals(CompositeViewFields.fromValue(viewValue), viewFields)
  }

}
