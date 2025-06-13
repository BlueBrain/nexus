package ai.senscience.nexus.delta.plugins.search

import ai.senscience.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ai.senscience.nexus.delta.plugins.search.model.{defaultProjectionId, defaultSourceId}
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.ElasticSearchProjectionFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.ProjectSourceFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewFields, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
object SearchViewFactory {

  private val searchGroup = Some(IndexGroup.unsafe("search"))

  /**
    * Create a composite view payload by injecting the value defined in the configuration
    */
  def apply(defaults: Defaults, config: IndexingConfig): CompositeViewFields =
    CompositeViewFields(
      Some(defaults.name),
      Some(defaults.description),
      NonEmptyList.of(ProjectSourceFields(id = Some(defaultSourceId))),
      NonEmptyList.of(
        ElasticSearchProjectionFields(
          id = Some(defaultProjectionId),
          query = config.query,
          mapping = config.mapping,
          indexGroup = searchGroup,
          context = config.context,
          settings = config.settings,
          resourceTypes = config.resourceTypes
        )
      ),
      config.rebuildStrategy
    )

  /**
    * Checks if the current value for the search view matches the configuration.
    *
    * Those checks are limited to the dynamic parts provided by the configuration as search views have a fixed shape
    * provided by [[SearchViewFactory.apply]]
    */
  def matches(current: CompositeViewValue, defaults: Defaults, config: IndexingConfig): Boolean = {
    current.name.contains(defaults.name) &&
    current.description.contains(defaults.description) &&
    current.projections(defaultProjectionId).exists {
      case _: SparqlProjection        =>
        throw new IllegalStateException("Search views do not include any SparqlProjection")
      case e: ElasticSearchProjection =>
        e.query == config.query &&
        e.mapping == config.mapping &&
        e.settings == config.settings &&
        e.context == config.context &&
        e.resourceTypes == config.resourceTypes
    } && current.rebuildStrategy == config.rebuildStrategy

  }

}
