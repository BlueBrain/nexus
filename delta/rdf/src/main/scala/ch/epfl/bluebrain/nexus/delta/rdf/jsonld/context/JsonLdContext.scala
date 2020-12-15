package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import monix.bio.IO

/**
  * A Json-LD context with its relevant fields.
  *
  * @param value          the value of the @context key
  * @param base           the Iri value of the @base key if present
  * @param vocab          the Iri value of the @vocab key if present
  * @param aliases        the @context aliases used to compact or shorten keys/values
  * @param prefixMappings the @context prefix mappings used to form CURIES
  */
final case class JsonLdContext(
    value: ContextValue,
    base: Option[Iri] = None,
    vocab: Option[Iri] = None,
    aliases: Map[String, Iri] = Map.empty,
    prefixMappings: Map[String, Iri] = Map.empty
) {

  /**
    * The inverse of the aliases. When a same Iri has multiple prefixes, the first alphabetically is chosen
    */
  lazy val aliasesInv: Map[Iri, String] = aliases.foldLeft(Map.empty[Iri, String]) { case (acc, (prefix, iri)) =>
    acc.updatedWith(iri)(_.fold(Some(prefix))(cur => Some(min(cur, prefix))))
  }

  /**
    * The inverse of the prefix mappings. When a same Iri has multiple prefixes, the first alphabetically is chosen
    */
  lazy val prefixMappingsInv: Map[Iri, String] = prefixMappings.foldLeft(Map.empty[Iri, String]) {
    case (acc, (prefix, iri)) => acc.updatedWith(iri)(_.fold(Some(prefix))(cur => Some(min(cur, prefix))))
  }

  /**
    * Attempts to construct a short form alias from the passed ''iri'' using the aliases.
    */
  def alias(iri: Iri): Option[String] =
    aliasesInv.get(iri)

  /**
    * Attempts to construct a CURIE from the passed ''iri'' using the prefixMappings.
    */
  def curie(iri: Iri): Option[String] =
    prefixMappingsInv.collectFirst {
      case (iriPm, prefix) if iri.startsWith(iriPm) => s"$prefix:${iri.stripPrefix(iriPm)}"
    }

  /**
    * Attempts to shorten the passed ''iri'' when it starts with the ''vocab''
    */
  def compactVocab(iri: Iri): Option[String] =
    vocab.collect { case v if iri.startsWith(v) => iri.stripPrefix(v) }

  /**
    * Attempts to shorten the passed ''iri'' when it starts with the ''base''
    */
  def compactBase(iri: Iri): Option[String]  =
    base.collect { case b if iri.startsWith(b) => iri.stripPrefix(b) }

  /**
    * Compact the ''passed'' iri:
    * 1. Attempt compacting using the aliases
    * 2. Attempt compacting using the vocab or base
    * 3. Attempt compacting using the prefix mappings to create a CURIE
    */
  def compact(iri: Iri, useVocab: Boolean): String = {
    lazy val compactedVocabOrBase = if (useVocab) compactVocab(iri) else compactBase(iri)
    alias(iri).orElse(compactedVocabOrBase).orElse(curie(iri)).getOrElse(iri.toString)
  }

  /**
    * Expands the passed value if it is in the form of a CURIE existing on the current context.
    * E.g.: schema:Person will expand to http://schema.org/Person if in the ''prefixMappings'' exists the key values
    * (schema -> http://schema.org/)
    */
  def expandCurie(value: String): Option[Iri] =
    value.split(":").toList match {
      case prefix :: suffix :: Nil if prefix.nonEmpty && suffix.nonEmpty && prefixMappings.contains(prefix) =>
        expandWith(prefixMappings(prefix), suffix)
      case _                                                                                                =>
        None
    }

  private def expandWith(iri: Iri, suffix: String): Option[Iri] =
    Iri.absolute(s"$iri$suffix").toOption

  /**
    * Expand the ''passed'' string:
    * 1. Attempt expanding using the aliases
    * 2. Attempt expanding using the prefix mappings to create a CURIE
    * 3. Attempt expanding to absolute Iri
    * 4. Attempt expanding using the vocab or base
    */
  def expand(value: String, useVocab: Boolean): Option[Iri] = {
    def expandedVocabOrBase =
      if (useVocab) vocab.flatMap(expandWith(_, value)) else base.flatMap(expandWith(_, value))

    aliases.get(value) orElse expandCurie(value) orElse Iri.absolute(value).toOption orElse expandedVocabOrBase
  }

  /**
    * @return true if the current context value is empty, false otherwise
    */
  def isEmpty: Boolean = value.isEmpty

  /**
    * The context object. E.g.: {"@context": {...}}
    */
  def contextObj: JsonObject = value.contextObj

  /**
    * Add a prefix mapping to the current context.
    *
    * @param prefix the prefix that can be used to create curies
    * @param iri    the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addPrefix(prefix: String, iri: Iri): JsonLdContext =
    copy(value = value.add(prefix, iri.asJson), prefixMappings = prefixMappings + (prefix -> iri))

  /**
    * Combines the current [[JsonLdContext]] context with a passed [[JsonLdContext]] context.
    * If a keys are is repeated in both contexts, the one in ''that'' will override the current one.
    *
    * @param that another context to be merged with the current
    * @return the merged context
    */
  def merge(that: JsonLdContext): JsonLdContext          =
    JsonLdContext(
      value.merge(that.value),
      that.base.orElse(base),
      that.vocab.orElse(vocab),
      aliases ++ that.aliases,
      prefixMappings ++ that.prefixMappings
    )

  /**
    * Add an alias to the current context.
    *
    * @param prefix the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri    the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addAlias(prefix: String, iri: Iri): JsonLdContext =
    addAlias(prefix, iri, None)

  /**
    * Add an alias to the current context.
    *
    * @param prefix  the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri     the iri which replaces the ''prefix'' when expanding JSON-LD
    * @param dataType the @type Iri value
    */
  def addAlias(prefix: String, iri: Iri, dataType: Iri): JsonLdContext =
    addAlias(prefix, iri, Some(dataType.toString))

  /**
    * Add an alias to the current context with the @type = @id.
    *
    * @param prefix  the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri     the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addAliasIdType(prefix: String, iri: Iri): JsonLdContext =
    addAlias(prefix, iri, Some(keywords.id))

  private def addAlias(prefix: String, iri: Iri, dataType: Option[String]): JsonLdContext =
    copy(
      value = value.add(prefix, dataType.fold(iri.asJson)(dt => expandedTermDefinition(dt, iri))),
      aliases = aliases + (prefix -> iri)
    )

  private def expandedTermDefinition(dt: String, iri: Iri): Json =
    Json.obj(keywords.tpe -> dt.asJson, keywords.id -> iri.asJson)

  private def min(a: String, b: String): String                  =
    if (a.compareTo(b) > 0) b else a
}

object JsonLdContext {

  /**
    * An empty [[JsonLdContext]] with no fields
    */
  val empty: JsonLdContext = JsonLdContext(ContextValue.empty)

  object keywords {
    val context = "@context"
    val id      = "@id"
    val tpe     = "@type"
    val list    = "@list"
    val base    = "@base"
    val vocab   = "@vocab"
    val graph   = "@graph"
    val value   = "@value"
  }

  /**
    * Construct a [[JsonLdContext]] from the passed ''context'' value using the JsonLd api
    */
  def apply(
      contextValue: ContextValue
  )(implicit api: JsonLdApi, resolution: RemoteContextResolution, opts: JsonLdOptions): IO[RdfError, JsonLdContext] =
    api.context(contextValue)

  /**
    * @return the value of the top @context key when found, an empty Json otherwise
    */
  def topContextValueOrEmpty(json: Json): ContextValue =
    ContextValue(
      json
        .arrayOrObject(
          None,
          arr => arr.singleEntryOr(Json.obj()).flatMap(_.asObject).flatMap(_(keywords.context)),
          obj => obj(keywords.context)
        )
        .getOrElse(Json.obj())
    )

  /**
    * @return the all the values with key @context
    */
  def contextValues(json: Json): Set[ContextValue] =
    json.extractValuesFrom(keywords.context).map { ctxValue =>
      topContextValueOrEmpty(Json.obj(keywords.context -> ctxValue))
    }

  /**
    * Merges the values of the key @context in both passed ''json'' and ''that'' Json documents.
    *
    * @param json the primary context. E.g.: {"@context": {...}}
    * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original json and the merged context of both passed jsons.
    *         If a key inside the @context is repeated in both jsons, the one in ''that'' will override the one in ''json''
    */
  def addContext(json: Json, that: Json): Json =
    json deepMerge topContextValueOrEmpty(json).merge(topContextValueOrEmpty(that)).contextObj.asJson

  /**
    * Adds a context Iri to an existing @context, or creates an @context with the Iri as a value.
    */
  def addContext(json: Json, contextIri: Iri): Json = {
    val jUriString = Json.fromString(contextIri.toString)

    json.asObject match {
      case Some(obj) =>
        val updated = obj(keywords.context) match {
          case None           => obj.add(keywords.context, jUriString)
          case Some(ctxValue) =>
            (ctxValue.asObject, ctxValue.asArray, ctxValue.asString) match {
              case (Some(co), _, _) if co.isEmpty                         => obj.add(keywords.context, jUriString)
              case (_, Some(ca), _) if ca.isEmpty                         => obj.add(keywords.context, jUriString)
              case (_, _, Some(cs)) if cs.isEmpty                         => obj.add(keywords.context, jUriString)
              case (Some(co), _, _) if !co.values.exists(_ == jUriString) =>
                obj.add(keywords.context, Json.arr(ctxValue, jUriString))
              case (_, Some(ca), _) if !ca.contains(jUriString)           =>
                obj.add(keywords.context, Json.fromValues(ca :+ jUriString))
              case (_, _, Some(cs)) if cs != contextIri.toString          =>
                obj.add(keywords.context, Json.arr(ctxValue, jUriString))
              case _                                                      => obj
            }
        }
        Json.fromJsonObject(updated)
      case None      => json
    }
  }
}
