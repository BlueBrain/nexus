package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue.indexingEq
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import munit.FunSuite

class CompositeViewValueSuite extends FunSuite with CompositeViewsFixture {

  test("Indexing Eq should correctly distinguish non equal values") {
    val viewValue1 = viewValue.copy(
      sources = NonEmptySet.of(projectSource),
      projections = NonEmptySet.of(esProjection)
    )
    val viewValue2 = viewValue.copy(
      sources = NonEmptySet.of(projectSource.copy(resourceTypes = Set(iri"https://schema.org/Person"))),
      projections = NonEmptySet.of(esProjection.copy(resourceTypes = Set(iri"https://schema.org/Person")))
    )
    assert(indexingEq.neqv(viewValue1, viewValue2))
  }

}
