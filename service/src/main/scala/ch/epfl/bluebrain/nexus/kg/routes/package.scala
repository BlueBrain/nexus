package ch.epfl.bluebrain.nexus.kg

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.{MalformedQueryParamRejection, Route}
import cats.Functor
import cats.data.{EitherT, OptionT}
import cats.instances.future._
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.{Ref, Rejection, ResourceV}
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.{DOT, Triples}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future

package object routes {

  private[routes] def completeWithFormat(
      fetched: Future[Either[Rejection, (StatusCode, ResourceV)]]
  )(implicit format: NonBinaryOutputFormat): Route =
    completeWithFormat(EitherT(fetched))

  private def completeWithFormat(
      fetched: EitherT[Future, Rejection, (StatusCode, ResourceV)]
  )(implicit format: NonBinaryOutputFormat): Route =
    format match {
      case f: JsonLDOutputFormat =>
        implicit val format = f
        complete(fetched.value)
      case Triples               =>
        implicit val format = Triples
        complete(fetched.map { case (status, resource) => status -> resource.value.graph.ntriples }.value)
      case DOT                   =>
        implicit val format = DOT
        complete(fetched.map { case (status, resource) => status -> resource.value.graph.dot() }.value)
    }

  private[routes] val read: Permission = Permission.unsafe("resources/read")

  private[routes] val schemaError =
    MalformedQueryParamRejection("schema", "The provided schema does not match the schema on the Uri")

  implicit private[routes] class FOptionSyntax[F[_], A](private val fOpt: F[Option[A]]) extends AnyVal {
    def toNotFound(id: AbsoluteIri)(implicit F: Functor[F]): EitherT[F, Rejection, A] =
      OptionT(fOpt).toRight(notFound(Ref(id)))
  }
}
