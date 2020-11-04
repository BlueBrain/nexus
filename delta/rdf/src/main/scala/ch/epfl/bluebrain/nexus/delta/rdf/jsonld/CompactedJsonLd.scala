package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

/**
  * Json-LD Compacted Document. This specific implementation is entity centric, having always only one root @id.
  *
  * The addition operations do not guarantee the proper compaction of those fields, neither guarantee the addition of
  * any necessary information into the context. This task is left to the developer to explicitly update the context
  */
final case class CompactedJsonLd private[jsonld] (
    obj: JsonObject,
    ctx: ContextValue,
    rootId: IriOrBNode
) extends JsonLd { self =>

  override type This = CompactedJsonLd

  protected type Predicate = String

  lazy val json: Json =
    obj.asJson.addContext(ctx.contextObj)

  def add(key: String, iri: Iri): This =
    add(key, iri.asJson)

  def addType(iri: Iri): This =
    add(keywords.tpe, iri)

  def add(key: String, literal: String): This =
    add(key, literal.asJson)

  def add(key: String, literal: Boolean): This =
    add(key, literal.asJson)

  def add(key: String, literal: Int): This =
    add(key, literal.asJson)

  def add(key: String, literal: Long): This =
    add(key, literal.asJson)

  def add(key: String, literal: Double): This =
    add(key, literal.asJson)

  def toCompacted(context: Json)(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd] =
    if (context.topContextValueOrEmpty == ctx) IO.pure(self)
    else JsonLd.compact(json, context, rootId)

  override def toExpanded(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] =
    JsonLd.expand(json).map(_.replaceId(rootId))

  override def toGraph(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Graph] =
    toExpanded.flatMap(_.toGraph)

  private def add(key: String, value: Json): This = {
    val newObj = obj(key) match {
      case Some(curr) =>
        obj.add(key, curr.arrayOrObject(Json.arr(curr, value).asJson, arr => (arr :+ value).asJson, _ => value))
      case None       =>
        obj.add(key, value)
    }
    copy(obj = newObj)
  }

}
