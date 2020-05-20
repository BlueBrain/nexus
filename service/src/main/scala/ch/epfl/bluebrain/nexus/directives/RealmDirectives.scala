package ch.epfl.bluebrain.nexus.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.epfl.bluebrain.nexus.AbsoluteUri
import ch.epfl.bluebrain.nexus.realms.RealmLabel
import ch.epfl.bluebrain.nexus.routes.SearchParams

trait RealmDirectives {

  /**
    * Matches a path segment as a [[RealmLabel]].
    */
  def realmLabel: Directive1[RealmLabel] =
    pathPrefix(Segment).flatMap { str =>
      RealmLabel(str) match {
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
      parameter("createdBy".as[Uri].?) &
      parameter("updatedBy".as[Uri].?) &
      parameter("type".as[Uri].*)).tmap {
      case (deprecated, rev, createdBy, updatedBy, types) =>
        SearchParams(deprecated, rev, createdBy, updatedBy, types.toSet)
    }

  implicit private def uriFromStringUnmarshaller: Unmarshaller[String, Uri] =
    Unmarshaller.strict[String, Uri] { string =>
      AbsoluteUri(string) match {
        case Right(iri) => iri
        case _          => throw new IllegalArgumentException(s"'$string' is not a valid Uri")
      }
    }
}

object RealmDirectives extends RealmDirectives
