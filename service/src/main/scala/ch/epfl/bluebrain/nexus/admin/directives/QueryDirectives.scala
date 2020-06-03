package ch.epfl.bluebrain.nexus.admin.directives

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.PaginationConfig
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * Collection of query specific directives.
  */
trait QueryDirectives {

  /**
    * Extracts pagination specific query params from the request or defaults to the preconfigured values.
    *
    * @param qs the preconfigured query settings
    */
  def paginated(implicit qs: PaginationConfig): Directive1[FromPagination] =
    (parameter("from".as[Int] ? qs.default.from) & parameter("size".as[Int] ? qs.default.size)).tmap {
      case (from, size) => FromPagination(from.max(0), size.max(0).min(qs.maxSize))
    }

  /**
    * @return the extracted search parameters from the request query parameters for projects listings.
    */
  def searchParamsProjects: Directive1[SearchParams] =
    parameter("label".as[String].?).flatMap { label =>
      searchParams.map(_.copy(projectLabel = label.filter(_.nonEmpty).map(Field(_))))
    }

  /**
    * @return the extracted search parameters from the request query parameters for organizations listings.
    */
  def searchParamsOrgs: Directive1[SearchParams] =
    parameter("label".as[String].?).flatMap { label =>
      searchParams.map(_.copy(organizationLabel = label.filter(_.nonEmpty).map(Field(_))))
    }

  private def searchParams: Directive1[SearchParams] =
    (parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Long].?) &
      parameter("createdBy".as[AbsoluteIri].?) &
      parameter("updatedBy".as[AbsoluteIri].?) &
      parameter("type".as[AbsoluteIri].*)).tmap {
      case (deprecated, rev, createdBy, updatedBy, types) =>
        SearchParams(None, None, deprecated, rev, createdBy, updatedBy, types.toSet)
    }

  private implicit def iriFromStringUnmarshaller: Unmarshaller[String, AbsoluteIri] =
    Unmarshaller.strict[String, AbsoluteIri] { string =>
      Iri.url(string) match {
        case Right(iri) => iri
        case x          => throw new IllegalArgumentException(s"'$x' is not a valid AbsoluteIri value")
      }
    }
}

object QueryDirectives extends QueryDirectives
