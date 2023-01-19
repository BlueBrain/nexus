package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue.nextIndexingRev
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.views.PipeStep
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterDeprecated
import io.circe.JsonObject
import munit.FunSuite

class ElasticSearchViewValueSuite extends FunSuite {

  private val viewValue = IndexingElasticSearchViewValue(None, None)

  test("Views with non-reindexing differences") {
    val viewValues = List(
      IndexingElasticSearchViewValue(Some("name"), None),
      IndexingElasticSearchViewValue(None, Some("description")),
      IndexingElasticSearchViewValue(Some("name"), Some("description")),
      viewValue.copy(permission = permissions.read)
    )
    viewValues.foreach(v => assertEquals(nextIndexingRev(v, viewValue, 1), 1))
  }

  test("Views with different reindexing fields") {
    val viewValues = List(
      viewValue.copy(resourceTag = Some(UserTag.unsafe("tag"))),
      viewValue.copy(pipeline = List(PipeStep(FilterDeprecated.ref.label, None, None))),
      viewValue.copy(mapping = Some(JsonObject.empty)),
      viewValue.copy(settings = Some(JsonObject.empty)),
      viewValue.copy(context = Some(ContextObject.apply(JsonObject.empty)))
    )
    viewValues.foreach(v => assertEquals(nextIndexingRev(v, viewValue, 1), 2))
  }

}
