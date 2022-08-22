package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.View
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import doobie.postgres.circe.jsonb.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDependencyStore, Predicate, Serializer}
import com.typesafe.scalalogging.Logger
import doobie._
import doobie.implicits._
import io.circe.{Decoder, Json}
import monix.bio.{IO, UIO}

trait ViewsStore[Rejection] {

  /**
    * Fetch the view with the given id in the given project and maps it to a view
    * @param id
    * @param project
    * @return
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[Rejection, View]

  /**
    * Fetch default views and combine them in an aggregate view
    * @param predicate
    *   to get all default view from the system / a given organization / a given project
    * @return
    */
  def fetchDefaultViews(predicate: Predicate): UIO[AggregateView]

}

object ViewsStore {

  private val logger: Logger = Logger[ViewsStore.type]

  def apply[Rejection, Value](
      entityType: EntityType,
      serializer: Serializer[Iri, Value],
      defaultViewId: Iri,
      fetchValue: (IdSegmentRef, ProjectRef) => IO[Rejection, Value],
      asView: Value => IO[Rejection, Either[Iri, IndexingView]],
      xas: Transactors
  ): ViewsStore[Rejection] = new ViewsStore[Rejection] {

    implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(s"${entityType.value}ViewsStore")

    implicit val stateDecoder: Decoder[Value] = serializer.codec

    // For embedded views in aggregate view drop intermediate aggregate view and those who raise an error
    private def embeddedView(value: Value): UIO[Option[IndexingView]] = asView(value).redeemWith(
      rejection => UIO.delay(logger.info(s"View '$value' is skipped because of '$rejection'.")) >> UIO.none,
      v =>
        UIO.delay(logger.debug(s"View '$value' is skipped because it is an intermediate aggregate view.")) >> UIO.pure(
          v.toOption
        )
    )

    override def fetch(id: IdSegmentRef, project: ProjectRef): IO[Rejection, View] =
      for {
        res              <- fetchValue(id, project).flatMap(asView)
        singleOrMultiple <- IO.fromEither(res).widen[View].onErrorHandleWith { iri =>
                              EntityDependencyStore.decodeRecursiveList[Iri, Value](project, iri, xas).flatMap {
                                _.traverseFilter(embeddedView).map(AggregateView(_))
                              }
                            }
      } yield singleOrMultiple

    override def fetchDefaultViews(predicate: Predicate): UIO[AggregateView] = {
      (fr"SELECT value FROM scoped_states" ++
        Fragments.whereAndOpt(
          Some(fr"type = $entityType"),
          predicate.asFragment,
          Some(fr"tag = ${Tag.Latest.value}"),
          Some(fr"id = $defaultViewId")
        ))
        .query[Json]
        .to[List]
        .transact(xas.read)
        .flatMap { rows =>
          rows.traverseFilter { r => IO.fromEither(r.as[Value]).hideErrors.flatMap(embeddedView) }.map(AggregateView(_))
        }
        .hideErrors
    }.span("fetchingDefaultViews")
  }
}
