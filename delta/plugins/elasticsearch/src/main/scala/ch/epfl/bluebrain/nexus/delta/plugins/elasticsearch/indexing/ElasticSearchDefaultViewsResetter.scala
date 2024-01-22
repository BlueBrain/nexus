package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.IO
import cats.effect.std.Env
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchDefaultViewsResetter.ViewElement
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.implicits._
import fs2.Stream

/**
  * A way to reset default Elasticsearch views
  */
trait ElasticSearchDefaultViewsResetter {
  def resetDefaultViews: IO[Unit]

  private[indexing] def resetView(view: ViewElement): IO[Unit]
}

object ElasticSearchDefaultViewsResetter {

  private val logger = Logger[ElasticSearchDefaultViewsResetter]

  sealed private[indexing] trait ViewElement {
    def project: ProjectRef

    def value: Option[IndexingViewDef]
  }

  private[indexing] object ViewElement {

    final case class MissingView(project: ProjectRef)        extends ViewElement {
      override def value: Option[IndexingViewDef] = None
    }
    final case class ExistingView(indexing: IndexingViewDef) extends ViewElement {
      override def project: ProjectRef = indexing.ref.project

      override def value: Option[IndexingViewDef] = Some(indexing)
    }

  }

  def resetTrigger: IO[Boolean] =
    Env[IO].get("RESET_DEFAULT_ES_VIEWS").map(_.getOrElse("false").toBoolean)

  /**
    * Provides a resetter that for each default elasticsearch view:
    *
    *   1. Deletes the associated index in elasticsearch 2. Deletes all associated events, states, and projection
    *      offsets 3. Creates a new default elasticsearch view using the provided new view value
    *
    * Note that it will reset the default view even its project is deprecated.
    */
  def apply(
      client: ElasticSearchClient,
      projects: Projects,
      views: ElasticSearchViews,
      newViewValue: ElasticSearchViewValue,
      xas: Transactors
  )(implicit subject: Subject): ElasticSearchDefaultViewsResetter =
    apply(
      projects.currentRefs.evalMap { project =>
        views
          .fetchIndexingView(defaultViewId, project)
          .redeem(_ => ViewElement.MissingView(project), ViewElement.ExistingView(_))
      },
      client.deleteIndex,
      views.internalCreate(_, _, _)(subject).void,
      newViewValue,
      resetTrigger,
      xas
    )

  def apply(
      views: Stream[IO, ViewElement],
      deleteIndex: IndexLabel => IO[Boolean],
      createView: (Iri, ProjectRef, ElasticSearchViewValue) => IO[Unit],
      newViewValue: ElasticSearchViewValue,
      resetTrigger: IO[Boolean],
      xas: Transactors
  ): ElasticSearchDefaultViewsResetter =
    new ElasticSearchDefaultViewsResetter {
      override def resetDefaultViews: IO[Unit] =
        resetTrigger.flatMap { triggered =>
          IO.whenA(triggered) {
            views.compile.toList
              .flatMap { _.traverse { resetView } }
              .flatMap { _ => logger.info("Completed resetting default elasticsearch views.") }
          }
        }

      override def resetView(view: ViewElement): IO[Unit] =
        deleteEsIndex(view) >>
          deleteEventsStatesOffsets(view.project).transact(xas.write) >>
          createDefaultView(view.project)

      private def deleteEsIndex(view: ViewElement) =
        view.value.traverse {
          case activeView: ActiveViewDef => deleteIndex(activeView.index)
          case _: DeprecatedViewDef      => IO.pure(true)
        }.void

      private def deleteEventsStatesOffsets(project: ProjectRef): doobie.ConnectionIO[Unit] =
        sql"""
          DELETE FROM scoped_events WHERE type = 'elasticsearch' AND id = ${defaultViewId.toString} AND org = ${project.organization} AND project = ${project.project};
          DELETE FROM scoped_states WHERE type = 'elasticsearch' AND id = ${defaultViewId.toString} AND org = ${project.organization} AND project = ${project.project};
          DELETE FROM projection_offsets WHERE module = 'elasticsearch' AND resource_id = ${defaultViewId.toString} AND project = $project;
        """.stripMargin.update.run.void

      private def createDefaultView(project: ProjectRef): IO[Unit] =
        createView(defaultViewId, project, newViewValue)
          .flatMap(_ => logger.info(s"Created a new defaultElasticSearchView in project '$project'."))
          .handleErrorWith(e => logger.error(s"Could not create view. Message: '${e.getMessage}'"))
          .void
    }

}
