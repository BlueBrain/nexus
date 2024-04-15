package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewValue, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphScopeInitialization, BlazegraphViews, ValidateBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, ValidateCompositeView}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchFiles, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchScopeInitialization, ElasticSearchViews, ValidateElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ScopeInitializationErrorStore}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sdk.{ScopeInitialization, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig

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
  )(implicit jsonLdApi: JsonLdApi) = {
    implicit val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)
    val prefix                                   = "nexus" // TODO: use the config?

    val noValidation = new ValidateElasticSearchView {
      override def apply(uuid: UUID, indexingRev: IndexingRev, v: ElasticSearchViewValue): IO[Unit] = IO.unit
    }

    ElasticSearchFiles.mk(loader).flatMap { files =>
      ElasticSearchViews(
        fetchContext,
        rcr,
        noValidation,
        config,
        prefix,
        xas,
        files.defaultMapping,
        files.defaultSettings,
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
  )(implicit jsonLdApi: JsonLdApi) = {
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

  def cvViews(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi) = {
    val noValidation = new ValidateCompositeView {
      override def apply(uuid: UUID, value: CompositeViewValue): IO[Unit] = IO.unit
    }
    (uuid: UUID) =>
      CompositeViews(
        fetchContext,
        rcr,
        noValidation,
        3.seconds, // TODO: use the config?
        config,
        xas,
        clock
      )(jsonLdApi, UUIDF.fixed(uuid))
  }

  def viewInitializer(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: ShipConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[ScopeInitializer] = {
    for {
      esViews <- elasticSearchViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      bgViews <- blazegraphViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
    } yield viewInitializer(esViews, bgViews, config, clock, xas)
  }

  private def viewInitializer(
      esViews: ElasticSearchViews,
      bgViews: BlazegraphViews,
      config: ShipConfig,
      clock: EventClock,
      xas: Transactors
  ): ScopeInitializer = {
    val viewInits = Set.empty[ScopeInitialization] +
      new ElasticSearchScopeInitialization(
        esViews,
        config.serviceAccount.value,
        config.viewDefaults.elasticsearch
      ) + new BlazegraphScopeInitialization(
        bgViews,
        config.serviceAccount.value,
        config.viewDefaults.blazegraph
      )
    ScopeInitializer(viewInits, ScopeInitializationErrorStore.apply(xas, clock))
  }

}
