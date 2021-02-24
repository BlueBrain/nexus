package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}

trait ElasticSearchViewsDirectives {
  private val defaultSort = SortList(List(Sort(nxv.createdAt.prefix), Sort("@id")))

  implicit private val sortFromStringUnmarshaller: FromStringUnmarshaller[Sort] =
    Unmarshaller.strict[String, Sort](Sort(_))

  /**
    * Extract the ''sort'' query parameter(s) and provide a [[SortList]]
    */
  def sortList: Directive1[SortList] =
    parameter("sort".as[Sort].*).map {
      case s if s.isEmpty => defaultSort
      case s              => SortList(s.toList.reverse)
    }
}

object ElasticSearchViewsDirectives extends ElasticSearchViewsDirectives
