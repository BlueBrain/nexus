package ch.epfl.bluebrain.nexus.iam.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.epfl.bluebrain.nexus.iam.routes.SearchParams
import ch.epfl.bluebrain.nexus.iam.types.Label
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

trait RealmDirectives {

  /**
    * Matches a path segment as a [[Label]].
    */
  def label: Directive1[Label] =
    pathPrefix(Segment).flatMap { str =>
      Label(str) match {
        case Left(err)    => reject(validationRejection(err))
        case Right(label) => provide(label)
      }
    }

  /**
    * @return the extracted search parameters from the request query parameters.
    */
  def searchParams: Directive1[SearchParams] =
    (parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Long].?) &
      parameter("createdBy".as[AbsoluteIri].?) &
      parameter("updatedBy".as[AbsoluteIri].?) &
      parameter("type".as[AbsoluteIri].*)).tmap {
      case (deprecated, rev, createdBy, updatedBy, types) =>
        SearchParams(deprecated, rev, createdBy, updatedBy, types.toSet)
    }

  implicit private def iriFromStringUnmarshaller: Unmarshaller[String, AbsoluteIri] =
    Unmarshaller.strict[String, AbsoluteIri] { string =>
      Iri.url(string) match {
        case Right(iri) => iri
        case x          => throw new IllegalArgumentException(s"'$x' is not a valid AbsoluteIri value")
      }
    }
}

object RealmDirectives extends RealmDirectives
