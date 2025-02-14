package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewValue, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphScopeInitialization, BlazegraphViews, ValidateBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, ValidateCompositeView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.views.DefaultIndexDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ValidateElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.InputConfig

import java.util.UUID
import scala.concurrent.duration.DurationInt

object ViewWiring {

  def elasticSearchViews(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      uuidF: UUIDF,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[ElasticSearchViews] = {
    implicit val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)
    val prefix                                   = "nexus" // TODO: use the config?

    val noValidation = new ValidateElasticSearchView {
      override def apply(uuid: UUID, indexingRev: IndexingRev, v: ElasticSearchViewValue): IO[Unit] = IO.unit
    }

    DefaultIndexDef(loader).flatMap { defaultIndex =>
      ElasticSearchViews(
        fetchContext,
        rcr,
        noValidation,
        config,
        prefix,
        xas,
        defaultIndex,
        clock
      )(jsonLdApi, uuidF)
    }
  }

  def blazegraphViews(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      uuidF: UUIDF,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[BlazegraphViews] = {
    val noValidation = new ValidateBlazegraphView {
      override def apply(value: BlazegraphViewValue): IO[Unit] = IO.unit
    }
    val prefix       = "nexus" // TODO: use the config?
    BlazegraphViews(
      fetchContext,
      rcr,
      noValidation,
      (_: ViewResource) => IO.unit,
      config,
      prefix,
      xas,
      clock
    )(jsonLdApi, uuidF)
  }

  def compositeViews(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      uuidF: UUIDF,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi) = {
    val noValidation = new ValidateCompositeView {
      override def apply(uuid: UUID, value: CompositeViewValue): IO[Unit] = IO.unit
    }
    CompositeViews(
      fetchContext,
      rcr,
      noValidation,
      3.seconds, // TODO: use the config?
      config,
      xas,
      clock
    )(jsonLdApi, uuidF)
  }

  def viewInitializers(
      bgViews: BlazegraphViews,
      config: InputConfig
  ): Set[ScopeInitialization] =
    Set.empty[ScopeInitialization] +
      new BlazegraphScopeInitialization(
        bgViews,
        config.serviceAccount.value,
        config.viewDefaults.blazegraph
      )

}
