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

  private def min(a: String, b: String): String =
    if (a.compareTo(b) > 0) b else a

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

  override def addPrefix(prefix: String, iri: IRI): This                                  =
    copy(value = add(prefix, iri.asJson), prefixMappings = prefixMappings + (prefix -> iri))

  protected def addAlias(prefix: String, iri: IRI, dataType: Option[String] = None): This =
    copy(
      value = add(prefix, dataType.fold(iri.asJson)(dt => expandedTermDefinition(dt, iri))),
      aliases = aliases + (prefix -> iri)
    )
}
