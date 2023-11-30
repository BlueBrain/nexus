package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.IO
import cats.effect.std.Env
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewIsDeprecated
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import doobie.implicits._
import fs2.Stream

/** A way to reset default Elasticsearch views */
trait ElasticSearchDefaultViewsResetter {
  def resetDefaultViews: IO[Unit]
}

object ElasticSearchDefaultViewsResetter {

  private val logger = Logger[ElasticSearchDefaultViewsResetter]

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
      views: ElasticSearchViews,
      projects: Projects,
      newViewValue: ElasticSearchViewValue,
      xas: Transactors
  )(implicit subject: Subject): ElasticSearchDefaultViewsResetter =
    apply(
      client.deleteIndex,
      views.fetchIndexingView,
      views.unsafeCreate(_, _, _)(subject).void,
      projects.currentRefs,
      newViewValue,
      resetTrigger,
      xas
    )

  def apply(
      deleteIndex: IndexLabel => IO[Boolean],
      fetchIndexingView: (IdSegmentRef, ProjectRef) => IO[ActiveViewDef],
      unsafeCreate: (Iri, ProjectRef, ElasticSearchViewValue) => IO[Unit],
      projects: Stream[IO, ProjectRef],
      newViewValue: ElasticSearchViewValue,
      resetTrigger: IO[Boolean],
      xas: Transactors
  ): ElasticSearchDefaultViewsResetter =
    new ElasticSearchDefaultViewsResetter {
      override def resetDefaultViews: IO[Unit] =
        resetTrigger.flatMap { triggered =>
          IO.whenA(triggered) {
            projects
              .evalTap { project =>
                deleteEsIndex(project) >>
                  deleteEventsStatesOffsets(project).transact(xas.write) >>
                  createDefaultView(project)
              }
              .compile
              .drain
          }
        }

      private val defaultEsViewId = "https://bluebrain.github.io/nexus/vocabulary/defaultElasticSearchIndex"

      private def deleteEsIndex(project: ProjectRef): IO[Unit] =
        fetchIndexingView(defaultEsViewId, project)
          .flatMap(v => deleteIndex(v.index))
          .handleErrorWith {
            case ViewIsDeprecated(_) => logger.info(s"The default view in project '$project' was already deprecated.")
            case e                   =>
              logger.warn(
                s"There was an error when attempting to delete the default ES index in project '$project'. Reason: ${e.getMessage}"
              )
          }
          .void

      private def deleteEventsStatesOffsets(project: ProjectRef): doobie.ConnectionIO[Unit] =
        sql"""
          DELETE FROM scoped_events WHERE type = 'elasticsearch' AND id = $defaultEsViewId AND org = ${project.organization} AND project = ${project.project};
          DELETE FROM scoped_states WHERE type = 'elasticsearch' AND id = $defaultEsViewId AND org = ${project.organization} AND project = ${project.project};
          DELETE FROM projection_offsets WHERE module = 'elasticsearch' AND resource_id = $defaultEsViewId AND project = $project;
        """.stripMargin.update.run.void

      private def createDefaultView(project: ProjectRef): IO[Unit] =
        unsafeCreate(defaultViewId, project, newViewValue)
          .flatMap(_ => logger.info(s"Created a new defaultElasticSearchView in project '$project'."))
          .handleErrorWith(e => logger.error(s"Could not create view. Message: '${e.getMessage}'"))
          .void

    }

}
