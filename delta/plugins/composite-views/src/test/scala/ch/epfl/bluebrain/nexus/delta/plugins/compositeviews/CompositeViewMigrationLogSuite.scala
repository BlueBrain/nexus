package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite

class CompositeViewMigrationLogSuite extends SerializationSuite {

  test("ElasticSearchProjection that already have an includeContext field are left untouched") {
    val json         = jsonContentOf("composite-views/migration/view-created-with-include-context.json")
    val enrichedJson = CompositeViewsPluginModule.enrichCompositeViewEvent(json)
    enrichedJson.equalsIgnoreArrayOrder(json)
  }

  test("An ElasticSearchProjection has includeContext field injected if it doesn't exist") {
    val json         = jsonContentOf("composite-views/migration/view-created-no-include-context.json")
    val expectedJson = jsonContentOf("composite-views/migration/view-created-injected.json")
    val enrichedJson = CompositeViewsPluginModule.enrichCompositeViewEvent(json)
    enrichedJson.equalsIgnoreArrayOrder(expectedJson)
  }

  test("If a CompositeViewEvent doesn't have a value field, it is left unchanged") {
    val json         = jsonContentOf("composite-views/database/view-deprecated.json")
    val enrichedJson = CompositeViewsPluginModule.enrichCompositeViewEvent(json)
    enrichedJson.equalsIgnoreArrayOrder(json)
  }

}
