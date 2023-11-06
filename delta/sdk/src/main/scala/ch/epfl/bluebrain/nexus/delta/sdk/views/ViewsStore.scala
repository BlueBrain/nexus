package ch.epfl.bluebrain.nexus.delta.sdk.views

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDependencyStore, Serializer, Transactors}
import io.circe.Decoder
import monix.bio.{IO, UIO}

trait ViewsStore[Rejection] {

  /**
    * Fetch the view with the given id in the given project and maps it to a view
    * @param id
    *   the view identifier
    * @param project
    *   the view
    * @return
    */
  def fetch(id: IdSegmentRef, project: ProjectRef): IO[Rejection, View]

}

object ViewsStore {

  private val logger: Logger = Logger[ViewsStore.type]

  import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._

  def apply[Rejection, Value](
      serializer: Serializer[Iri, Value],
      fetchValue: (IdSegmentRef, ProjectRef) => IO[Rejection, Value],
      asView: Value => IO[Rejection, Either[Iri, IndexingView]],
      xas: Transactors
  ): ViewsStore[Rejection] = new ViewsStore[Rejection] {

    implicit val stateDecoder: Decoder[Value] = serializer.codec

    // For embedded views in aggregate view drop intermediate aggregate view and those who raise an error
    private def embeddedView(project: ProjectRef, id: Iri, value: Value): UIO[Option[IndexingView]] =
      asView(value).redeemWith(
        rejection => logger.debug(s"View '$id' in project '$project' is skipped because of '$rejection'.") >> UIO.none,
        v =>
          logger.debug(
            s"View '$id' in project '$project' is skipped because it is an intermediate aggregate view."
          ) >> UIO.pure(
            v.toOption
          )
      )

    override def fetch(id: IdSegmentRef, project: ProjectRef): IO[Rejection, View] =
      for {
        res              <- fetchValue(id, project).flatMap(asView)
        singleOrMultiple <- IO.fromEither(res).widen[View].onErrorHandleWith { iri =>
                              EntityDependencyStore.decodeRecursiveDependencies[Iri, Value](project, iri, xas).flatMap {
                                _.traverseFilter(embeddedView(project, iri, _)).map(AggregateView(_))
                              }
                            }
      } yield singleOrMultiple

  }
}
