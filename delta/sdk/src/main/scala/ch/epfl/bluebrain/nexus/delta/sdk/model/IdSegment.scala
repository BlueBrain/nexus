package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase}

/**
  * A segment from the positional API that should be an Id
  */
sealed trait IdSegment extends Product with Serializable { self =>

  /**
    * @return
    *   the string value of the segment
    */
  def asString: String

  /**
    * @return
    *   Some(iri) when conversion was successful using the api mappings and project base if needed, None otherwise
    */
  def toIri(mappings: ApiMappings, base: ProjectBase): Option[Iri]

  override def toString: String = asString
}

object IdSegment {

  implicit def iriToIriSegment(iri: Iri): IdSegment             = IriSegment(iri)
  implicit def refToIriSegment(iri: ResourceRef): IdSegment     = IriSegment(iri.original)
  implicit def stringToStringSegment(string: String): IdSegment = StringSegment(string)

  /**
    * Construct an [[IdSegment]] from the passed ''string''
    */
  final def apply(string: String): IdSegment =
    Iri.absolute(string).fold[IdSegment](_ => StringSegment(string), IriSegment)

  /**
    * A segment that holds a free form string (which can expand into an Iri)
    */
  final case class StringSegment(value: String) extends IdSegment {
    override val asString: String = value

    override def toIri(mappings: ApiMappings, base: ProjectBase): Option[Iri] = {
      val ctx = JsonLdContext(
        ContextValue.empty,
        base = Some(base.iri),
        prefixMappings = mappings.prefixMappings,
        aliases = mappings.aliases
      )
      ctx.expand(value, useVocab = false)
    }
  }

  /**
    * A segment that holds an [[Iri]]
    */
  final case class IriSegment(value: Iri) extends IdSegment {
    override def asString: String                                             = value.toString
    override def toIri(mappings: ApiMappings, base: ProjectBase): Option[Iri] =
      if (value.scheme.exists(mappings.prefixMappings.contains))
        StringSegment(value.toString).toIri(mappings, base) orElse Some(value)
      else
        Some(value)
  }
}
