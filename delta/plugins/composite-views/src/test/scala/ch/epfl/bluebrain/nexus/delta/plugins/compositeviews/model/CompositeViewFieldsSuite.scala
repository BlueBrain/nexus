package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields.indexingEq
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import munit.FunSuite

class CompositeViewFieldsSuite extends FunSuite with CompositeViewsFixture {

  test("Indexing Eq should correctly distinguish non equal values") {
    val viewValue1 = viewFields.copy(
      sources = NonEmptySet.of(projectFields),
      projections = NonEmptySet.of(esProjectionFields)
    )
    val viewValue2 = viewFields.copy(
      sources = NonEmptySet.of(projectFields.copy(resourceTypes = Set(iri"https://schema.org/Person"))),
      projections = NonEmptySet.of(esProjectionFields.copy(resourceTypes = Set(iri"https://schema.org/Person")))
    )
    assert(indexingEq.neqv(viewValue1, viewValue2))
  }

  test("A CompositeViewValue should be correctly converted to CompositeViewFields") {
    assertEquals(CompositeViewFields.fromValue(viewValue), viewFields)
  }

}
