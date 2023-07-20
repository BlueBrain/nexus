package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.Eq
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeProjectionLifeCycle.Hook
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields.indexingEq
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization.defaultSearchCompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import monix.bio.{Task, UIO}

final class SearchConfigHook(
    defaults: Defaults,
    config: IndexingConfig,
    update: (ActiveViewDef, CompositeViewFields) => UIO[Unit]
) extends Hook {

  private val defaultSearchViewFields                         = defaultSearchCompositeViewFields(defaults, config)
  override def apply(view: ActiveViewDef): Option[Task[Unit]] =
    Option.when(viewIsDefault(view) && configHasChanged(view))(update(view, defaultSearchViewFields))

  private def configHasChanged(v: ActiveViewDef): Boolean = {
    implicit val eq: Eq[CompositeViewFields] = indexingEq
    CompositeViewFields.fromValue(v.value) =!= defaultSearchViewFields
  }

  private def viewIsDefault(v: ActiveViewDef): Boolean =
    v.ref.viewId == defaultViewId
}

object SearchConfigHook {

  private val logger: Logger = Logger[SearchConfigHook]

  def apply(compositeViews: CompositeViews, defaults: Defaults, indexingConfig: IndexingConfig)(implicit
      baseUri: BaseUri,
      subject: Subject
  ) = new SearchConfigHook(
    defaults,
    indexingConfig,
    update(compositeViews)
  )

  private def update(views: CompositeViews)(implicit
      subject: Subject,
      baseUri: BaseUri
  ): (ActiveViewDef, CompositeViewFields) => UIO[Unit] = { (viewDef: ActiveViewDef, fields: CompositeViewFields) =>
    views
      .update(viewDef.ref.viewId, viewDef.ref.project, viewDef.rev, fields)
      .redeemWith(
        e => logger.error(s"Could not update view '${viewDef.ref}'. Reason: '${e.reason}'"),
        _ => logger.info(s"Search view '${viewDef.ref}' has been successfully updated.")
      )
  }

}
