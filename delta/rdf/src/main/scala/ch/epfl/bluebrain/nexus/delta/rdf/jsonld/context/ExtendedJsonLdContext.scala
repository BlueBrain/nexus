package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.iri.IRI

/**
  * A Json-LD context that has been inspected to obtain its relevant fields.
  *
 * @param value          the value of the @context key
  * @param base           the IRI value of the @base key if present
  * @param vocab          the IRI value of the @vocab key if present
  * @param aliases        the @context aliases used to compact or shorten keys/values
  * @param prefixMappings the @context prefix mappings used to form CURIES
  */
final case class ExtendedJsonLdContext(
    value: Json,
    base: Option[IRI] = None,
    vocab: Option[IRI] = None,
    aliases: Map[String, IRI] = Map.empty,
    prefixMappings: Map[String, IRI] = Map.empty
) extends JsonLdContext {

  type This = ExtendedJsonLdContext

  /**
    * The inverse of the aliases. When a same IRI has multiple prefixes, the first alphabetically is chosen
    */
  lazy val aliasesInv: Map[IRI, String] = aliases.foldLeft(Map.empty[IRI, String]) {
    case (acc, (prefix, iri)) => acc.updatedWith(iri)(_.fold(Some(prefix))(cur => Some(min(cur, prefix))))
  }

  /**
    * The inverse of the prefix mappings. When a same IRI has multiple prefixes, the first alphabetically is chosen
    */
  lazy val prefixMappingsInv: Map[IRI, String] = prefixMappings.foldLeft(Map.empty[IRI, String]) {
    case (acc, (prefix, iri)) => acc.updatedWith(iri)(_.fold(Some(prefix))(cur => Some(min(cur, prefix))))
  }

  /**
    * Attempts to construct a short form alias from the passed ''iri'' using the aliases.
    */
  def alias(iri: IRI): Option[String] =
    aliasesInv.get(iri)

  /**
    * Attempts to construct a CURIE from the passed ''iri'' using the prefixMappings.
    */
  def curie(iri: IRI): Option[String] =
    prefixMappingsInv.collectFirst {
      case (iriPm, prefix) if iri.startsWith(iriPm) => s"$prefix:${iri.stripPrefix(iriPm)}"
    }

  /**
    * Attempts to shorten the passed ''iri'' when it starts with the ''vocab''
    */
  def compactVocab(iri: IRI): Option[String] =
    vocab.collect { case v if iri.startsWith(v) => iri.stripPrefix(v) }

  /**
    * Attempts to shorten the passed ''iri'' when it starts with the ''base''
    */
  def compactBase(iri: IRI): Option[String]  =
    base.collect { case b if iri.startsWith(b) => iri.stripPrefix(b) }

  /**
    * Compact the ''passed'' iri:
    * 1. Attempt compacting using the aliases
    * 2. Attempt compacting using the vocab or base
    * 3. Attempt compacting using the prefix mappings to create a CURIE
    */
  def compact(iri: IRI, useVocab: Boolean): String = {
    lazy val compactedVocabOrBase = if (useVocab) compactVocab(iri) else compactBase(iri)
    alias(iri).orElse(compactedVocabOrBase).orElse(curie(iri)).getOrElse(iri.toString)
  }

  def addPrefix(prefix: String, iri: IRI): This                                           =
    copy(value = add(prefix, iri.asJson), prefixMappings = prefixMappings + (prefix -> iri))

  protected def addAlias(prefix: String, iri: IRI, dataType: Option[String] = None): This =
    copy(
      value = add(prefix, dataType.fold(iri.asJson)(dt => expandedTermDefinition(dt, iri))),
      aliases = aliases + (prefix -> iri)
    )

  private def min(a: String, b: String): String =
    if (a.compareTo(b) > 0) b else a
}

object ExtendedJsonLdContext {
  val empty: ExtendedJsonLdContext = ExtendedJsonLdContext(Json.obj())
}
