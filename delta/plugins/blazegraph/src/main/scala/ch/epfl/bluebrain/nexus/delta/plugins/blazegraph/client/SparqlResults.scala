package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.instances._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import org.apache.jena.graph.Node

/**
  * Sparql query results representation.
  *
  * @param head    the variables mentioned in the results and may contain a "link" member
  * @param boolean an optional evaluator for the ASK query
  * @param results a collection of bindings
  */
final case class SparqlResults(head: Head, results: Bindings, boolean: Option[Boolean] = None) {

  private val s   = "subject"
  private val p   = "predicate"
  private val o   = "object"
  private val spo = Set(s, p, o)

  /**
    * Creates a new sparql result which is a merge of the provided results and the current results
    * @param that the provided head
    */
  def ++(that: SparqlResults): SparqlResults = SparqlResults(head ++ that.head, results ++ that.results)

  /**
    * Attempts to convert the Query Results JSON Format into a Graph.
    * This is useful for results of CONSTRUCT queries
    */
  def asGraph: Either[ConversionError, Graph] =
    Option
      .when(spo.subsetOf(head.vars.toSet)) {
        val totalTriples = results.bindings.foldLeft(Set.empty[Triple]) {
          case (triples, map) if spo.subsetOf(map.keySet) =>
            triples ++ (map(s).asSubject, map(p).asPredicate, map(o).asObject).mapN((_, _, _))
          case (triples, _)                               =>
            triples
        }
        Graph.empty.add(totalTriples)
      }
      .toRight(
        ConversionError(
          s"SparqlResults binding variables '${head.vars.mkString(",")}' do not contain '${spo.mkString(",")}'",
          "SparqlResults to Graph"
        )
      )
}

object SparqlResults {

  /**
    * Empty SparqlResults
    */
  val empty: SparqlResults = SparqlResults(Head(List.empty), Bindings(List.empty))

  /**
    * The "head" member gives the variables mentioned in the results and may contain a "link" member.
    *
    * @param vars  an array giving the names of the variables used in the results.
    *              These are the projected variables from the query.
    *              A variable is not necessarily given a value in every query solution of the results.
    * @param link an array of URIs, as strings, to refer for further information.
    *              The format and content of these link references is not defined by this document.
    */
  final case class Head(vars: List[String] = List.empty, link: Option[List[Uri]] = None) {

    /**
      * Creates a new head which is a merge of the provided head and the current head
      * @param that the provided head
      */
    def ++(that: Head): Head = {
      val newLink = (link ++ that.link).flatten.toList
      Head((vars ++ that.vars).distinct, if (newLink.isEmpty) None else Some(newLink))
    }
  }

  /**
    * The value of the "bindings" member is a map with zero or more elements, one element per query solution.
    * Each query solution is a Binding object. Each key of this object is a variable name from the query solution.
    */
  final case class Bindings(bindings: List[Map[String, Binding]]) {

    /**
      * Creates a new bindings which is a merge of the provided bindings and the current bindings
      * @param that the provided head
      */
    def ++(that: Bindings): Bindings = Bindings(bindings ++ that.bindings)
  }

  object Bindings {
    def apply(values: Map[String, Binding]*): Bindings = Bindings(values.toList)
  }

  /**
    * Encodes an RDF term
    *
    * @param `type`     the type of the term
    * @param value      the value of the term
    * @param `xml:lang` the language tag (when the term is a literal)
    * @param datatype   the data type information of the term
    */
  final case class Binding(
      `type`: String,
      value: String,
      `xml:lang`: Option[String] = None,
      datatype: Option[String] = None
  ) {

    /**
      * @return true when the current binding is a literal, false otherwise
      */
    def isLiteral: Boolean = `type` == "literal"

    /**
      * @return true when the current binding is an iri, false otherwise
      */
    def isIri: Boolean = `type` == "uri"

    /**
      * @return true when the current binding is a blank node, false otherwise
      */
    def isBNode: Boolean = `type` == "bnode"

    /**
      * Attempts to convert the current binding to a literal
      */
    def asLiteral: Option[Node] =
      if (isLiteral)
        Some(obj(value, datatype.flatMap(Iri.absolute(_).toOption), `xml:lang`))
      else
        None

    /**
      * Attempts to convert the current binding to a blank node
      */
    def asBNode: Option[BNode] =
      Option.when(isBNode)(BNode.unsafe(value))

    def asIri: Option[Iri] =
      Option.when(isIri)(Iri.absolute(value).toOption).flatten

    /**
      * Attempts to convert the current binding to an iri or a blank node
      */
    def asSubject: Option[Node] = (asIri orElse asBNode).map(subject)

    /**
      * Attempts to convert the current binding to a predicate
      */
    def asPredicate: Option[Node] = asIri.map(predicate)

    /**
      * Attempts to convert the current binding to an object
      */
    def asObject: Option[Node] = asLiteral orElse asSubject
  }

  implicit final val sparqlResultsEncoder: Encoder[SparqlResults] = deriveEncoder[SparqlResults]

  private val askResultDecoder: Decoder[SparqlResults] =
    Decoder.instance(_.get[Boolean]("boolean").map { boolean => SparqlResults(Head(), Bindings(), Some(boolean)) })

  implicit final val sparqlResultsDecoder: Decoder[SparqlResults] = {
    val default = deriveDecoder[SparqlResults]
    Decoder.instance(hc => default(hc) orElse askResultDecoder(hc))

  }
}
