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

  lazy val aliasesInv: Map[IRI, String] = aliases.map { case (prefix, iri) => iri -> prefix }

  lazy val prefixMappingsInv: Map[IRI, String] = prefixMappings.map { case (prefix, iri) => iri -> prefix }

  override def addPrefix(prefix: String, iri: IRI): This                                  =
    copy(value = add(prefix, iri.asJson), prefixMappings = prefixMappings + (prefix -> iri))

  protected def addAlias(prefix: String, iri: IRI, dataType: Option[String] = None): This =
    dataType match {
      case Some(dt) => copy(value = add(prefix, expandedTermDefinition(dt, iri)), aliases = aliases + (prefix -> iri))
      case None     => copy(value = add(prefix, iri.asJson), aliases = aliases + (prefix -> iri))
    }
}
