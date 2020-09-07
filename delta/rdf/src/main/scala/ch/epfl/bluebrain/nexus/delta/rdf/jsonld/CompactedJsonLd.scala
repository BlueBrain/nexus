package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO
import org.apache.jena.iri.IRI

/**
  * Json-LD Compacted Document. This specific implementation is entity centric, having always only one root @id.
  *
 * The addition operations do not guarantee the proper compaction of those fields, neither guarantee the addition of
  * any necessary information into the context. This task is left to the developer to explicitly update the context
  */
final case class CompactedJsonLd[Ctx <: JsonLdContext] private[jsonld] (
    obj: JsonObject,
    ctx: Ctx,
    rootId: IRI,
    ctxFields: ContextFields[Ctx]
) extends JsonLd { self =>

  type This                = CompactedJsonLd[Ctx]
  protected type Predicate = String

  lazy val json: Json = obj.asJson.addContext(Json.obj(keywords.context -> ctx.value))

  def add(key: String, iri: IRI): This =
    add(key, iri.asJson)

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

  def base(implicit ev: Ctx =:= ExtendedJsonLdContext): Option[IRI] =
    ctx.base

  def vocab(implicit ev: Ctx =:= ExtendedJsonLdContext): Option[IRI] =
    ctx.vocab

  def aliases(implicit ev: Ctx =:= ExtendedJsonLdContext): Map[String, IRI] =
    ctx.aliases

  def prefixMappings(implicit ev: Ctx =:= ExtendedJsonLdContext): Map[String, IRI] =
    ctx.prefixMappings

  @SuppressWarnings(Array("ComparingUnrelatedTypes"))
  def toCompacted[C <: JsonLdContext](context: Json, f: ContextFields[C])(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd[C]] = {
    lazy val ctxValue = context.topContextValueOrEmpty
    if (ctxValue == ctx.value) {
      if (f == self.ctxFields)
        IO.now(self.asInstanceOf[CompactedJsonLd[C]])
      else if (ctxFields == ContextFields.Include && f == ContextFields.Skip)
        IO.now(self.copy(ctx = RawJsonLdContext(ctx.value).asInstanceOf[C], ctxFields = f))
      else
        api.context(Json.obj(keywords.context -> ctx.value), f).map(ctx => self.copy(ctx = ctx, ctxFields = f))
    } else
      JsonLd.compact(json, context, rootId, f)
  }

  override def toExpanded(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] =
    JsonLd.expand(json).flatMap {
      case expanded if expanded.rootId != rootId => IO.raiseError(UnexpectedIri(rootId, expanded.rootId))
      case expanded                              => IO.now(expanded)
    }

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
