package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

/**
  * Enumeration of the possible JsonLdContext constructions
  */
sealed trait ContextFields[A <: JsonLdContext] extends Product with Serializable

object ContextFields {
  type Skip    = Skip.type
  type Include = Include.type

  /**
    * Skip the inspection of the context in order to obtain the @context fields
    */
  final case object Skip extends ContextFields[RawJsonLdContext]

  /**
    * Inspect the @context in order to obtain @context fields (@base, @vocab). This might have a performance penalty
    */
  final case object Include extends ContextFields[ExtendedJsonLdContext]
}
