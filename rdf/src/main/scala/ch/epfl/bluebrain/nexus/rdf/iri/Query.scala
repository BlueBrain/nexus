package ch.epfl.bluebrain.nexus.rdf.iri

import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.PctString._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._

import scala.collection.SortedMap
import scala.collection.immutable.SortedSet

/**
  * Query part of an Iri as defined in RFC 3987.
  *
  * @param value a seq of key values
  */
final case class Query private[iri] (value: Seq[(String, String)]) {

  /**
    * The query values on a sorted multi-map
    */
  lazy val sorted: SortedMap[String, SortedSet[String]] =
    SortedMap(value.groupBy(_._1).view.mapValues(e => SortedSet(e.map(_._2): _*)).toList: _*)

  def get(key: String): Set[String] =
    sorted.getOrElse(key, SortedSet.empty[String])

  /**
    * @return the string representation for the Query segment compatible with the rfc3987
    *         (using percent-encoding only for delimiters)
    */
  lazy val iriString: String =
    value
      .map {
        case (k, v) if v.isEmpty => k.pctEncodeIgnore(`iquery_allowed`)
        case (k, v)              => s"${k.pctEncodeIgnore(`iquery_allowed`)}=${v.pctEncodeIgnore(`iquery_allowed`)}"
      }
      .mkString("&")

  /**
    * @return the string representation using percent-encoding for the Query segment
    *         when necessary according to rfc3986
    */
  lazy val uriString: String =
    value
      .map {
        case (k, v) if v.isEmpty => k.pctEncodeIgnore(`query_allowed`)
        case (k, v)              => s"${k.pctEncodeIgnore(`query_allowed`)}=${v.pctEncodeIgnore(`query_allowed`)}"
      }
      .mkString("&")
}

object Query {

  /**
    * Attempts to parse the argument string as an `iquery` as defined by RFC 3987 and evaluate the key=value pairs.
    *
    * @param string the string to parse as a Query
    * @return Right(Query) if the parsing succeeds, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Query] =
    new IriParser(string).parseQuery

  final implicit val queryShow: Show[Query] = Show.show(_.iriString)

  final implicit val queryEq: Eq[Query] = (x: Query, y: Query) => x.sorted == y.sorted
}
