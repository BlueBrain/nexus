package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.iri.IRI

/**
  * A Json-LD context that contains only the value of the @context key.
  */
final case class RawJsonLdContext(value: Json) extends JsonLdContext {
  type This = RawJsonLdContext

  override def addPrefix(prefix: String, iri: IRI): This =
    RawJsonLdContext(add(prefix, iri.asJson))

  protected def addAlias(prefix: String, iri: IRI, dataType: Option[String] = None): This =
    dataType match {
      case Some(dt) => RawJsonLdContext(add(prefix, expandedTermDefinition(dt, iri)))
      case None     => RawJsonLdContext(add(prefix, iri.asJson))
    }

  override def merge(that: This): This =
    RawJsonLdContext(value.merge(that.value))
}

object RawJsonLdContext {
  val empty: RawJsonLdContext = RawJsonLdContext(Json.obj())
}
