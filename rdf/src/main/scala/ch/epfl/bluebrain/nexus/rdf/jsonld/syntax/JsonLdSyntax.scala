package ch.epfl.bluebrain.nexus.rdf.jsonld.syntax

import cats.Monad
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd.{ContextResolutionError, IdRetrievalError}
import ch.epfl.bluebrain.nexus.rdf.{Graph, Node}
import io.circe.Json

trait JsonLdSyntax {
  implicit final def contextSyntax(json: Json): JsonLdOps = new JsonLdOps(json)
  implicit final def graphSyntax(graph: Graph): GraphOps  = new GraphOps(graph)
}

final class GraphOps(private val graph: Graph) extends AnyVal {

  /**
    * Convert [[Graph]] to [[Json]] by applying provided context.
    * @param context  the context to apply
    * @return [[Json]] representation of the graph or error message
    */
  def toJson(context: Json = Json.obj()): Either[String, Json] = JsonLd.toJson(graph, context)
}

final class JsonLdOps(private val json: Json) extends AnyVal {

  /**
    * Resolve all IRIs inside @context.
    *
    * @param resolver context resolver
    * @return [[Json]] with all the context IRIs resolved
    */
  def resolveContext[F[_]: Monad](
      resolver: AbsoluteIri => F[Option[Json]]
  ): F[Either[ContextResolutionError, Json]] =
    JsonLd.resolveContext(json)(resolver)

  /**
    * @return a new Json with the values of all the ''@context'' keys
    */
  def contextValue: Json = JsonLd.contextValue(json)

  /**
    * Replaces the @context value from the provided json to the one in ''that'' json
    *
    * @param context the json with a @context to override the @context in the provided ''json''
    */
  def replaceContext(context: Json): Json = JsonLd.replaceContext(json, context)

  /**
    * Replaces the top @context value from the provided json to the provided ''iri''
    *
    * @param iri  the iri which overrides the existing json
    */
  def replaceContext(iri: AbsoluteIri): Json = JsonLd.replaceContext(json, iri)

  /**
    * Removes the provided keys from everywhere on the json.
    *
    * @param keys list of ''keys'' to be removed from the top level of the ''json''
    * @return the original json without the provided ''keys''
    */
  def removeNestedKeys(keys: String*): Json = JsonLd.removeNestedKeys(json, keys: _*)

  /**
    * Adds or merges a context URI to an existing JSON object.
    *
    * @param context the standard context URI
    * @return a new JSON object
    */
  def addContext(context: AbsoluteIri): Json = JsonLd.addContext(json, context)

  /**
    * @param  context the context to merge with this context . E.g.: {"@context": {...}}
    * @return a new Json with the values of the ''@context'' key (this) and the provided ''that'' top ''@context'' key
    *         If two keys inside both contexts collide, the one in the ''other'' context will override the one in this context
    */
  def mergeContext(context: Json): Json = JsonLd.mergeContext(json, context)

  /**
    * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original context plus the provided context
    */
  def appendContextOf(that: Json): Json = JsonLd.appendContextOf(json, that)

  /**
    * Attempts to find the top `@id` value on the provided json.
    *
    * @return Right(iri) if found, Left(error) otherwise
    */
  def id: Either[IdRetrievalError, AbsoluteIri] = JsonLd.id(json)

  /**
    * Adds @id value to the provided Json
    *
    * @param value the @id value
    */
  def id(value: AbsoluteIri): Json = JsonLd.id(json, value)

  /**
    * Removes the provided keys from the top object on the json.
    *
    * @param keys list of ''keys'' to be removed from the top level of the ''json''
    * @return the original json without the provided ''keys'' on the top level of the structure
    */
  def removeKeys(keys: String*): Json = JsonLd.removeKeys(json, keys: _*)

  /**
    * Retrieves the aliases on the provided ''json'' @context for the provided ''keyword''
    *
    * @param keyword the Json-LD keyword. E.g.: @id, @type, @container, @set
    * @return a set of aliases found for the given keyword
    */
  def contextAliases(keyword: String): Set[String] = JsonLd.contextAliases(json, keyword)

  /**
    * Convert [[Json]] object to graph
    * @param node [[Node]] to use as root node of the [[Graph]]
    * @return [[Graph]] representation of this [[Json]] or error message.
    */
  def toGraph(node: Node): Either[String, Graph] = JsonLd.toGraph(json, node)

}
