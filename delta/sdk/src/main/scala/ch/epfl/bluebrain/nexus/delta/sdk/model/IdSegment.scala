package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, ResourceRefSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, Project}

/**
  * A segment from the positional API that should be an Id
  */
sealed trait IdSegment extends Product with Serializable { self =>

  /**
    * @return the string value of the segment
    */
  def asString: String

  /**
    * Attempts to convert the current IdSegment into an [[Iri]] using the available [[IdSegmentExtractor]]
    *
    * @return Some(iri) when conversion was successful, None otherwise
    */
  def toIri(implicit extractor: IdSegmentExtractor[IdSegment]): Option[Iri] =
    extractor.toIri(self)

  /**
    * Attempts to convert the current IdSegment into a [[ResourceRef]] using the available [[IdSegmentExtractor]]
    *
    * @return Some(resourceRef) when conversion was successful, None otherwise
    */
  def toResourceRef(implicit extractor: IdSegmentExtractor[IdSegment]): Option[ResourceRef] =
    extractor.toResourceRef(self)

}

object IdSegment {

  /**
    * A segment that holds a free form string (which can expand into an Iri)
    */
  final case class StringSegment(value: String) extends IdSegment {
    override def asString: String = value
  }

  /**
    * A segment that holds an [[Iri]]
    */
  final case class IriSegment(value: Iri) extends IdSegment {
    override def asString: String = value.toString
  }

  /**
    * A segment that holds a [[ResourceRef]]
    */
  final case class ResourceRefSegment(value: ResourceRef) extends IdSegment {
    override def asString: String = value.iri.toString
  }
}

sealed trait IdSegmentExtractor[A <: IdSegment] {
  private[model] def toIri(segment: A): Option[Iri]
  private[model] def toResourceRef(segment: A): Option[ResourceRef]
}

object IdSegmentExtractor {

  implicit def idSegmentExtractor(implicit project: Project): IdSegmentExtractor[IdSegment] =
    new IdSegmentExtractor[IdSegment] {
      override private[model] def toIri(segment: IdSegment): Option[Iri]                 =
        segment match {
          case StringSegment(value)    => expandId(value)
          case IriSegment(iri)         => Some(iri)
          case ResourceRefSegment(ref) => Some(ref.original)
        }
      override private[model] def toResourceRef(segment: IdSegment): Option[ResourceRef] =
        segment match {
          case StringSegment(value)    => expandId(value).map(ResourceRef.apply)
          case IriSegment(iri)         => Some(ResourceRef(iri))
          case ResourceRefSegment(ref) => Some(ref)
        }
    }

  private def expandId(id: String)(implicit project: Project): Option[Iri] = {
    val mappings = project.apiMappings + ApiMappings.default
    val ctx      = JsonLdContext(ContextValue.empty, prefixMappings = mappings.prefixMappings, aliases = mappings.aliases)
    ctx.expand(id, useVocab = false)
  }
}
