package ch.epfl.bluebrain.nexus.rdf.derivation.configured

import cats.syntax.either._
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.{nxv, rdf}
import ch.epfl.bluebrain.nexus.rdf.iri.Iri
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri

/**
  * Graph Derivation configuration
  *
  * @param base                       the Uri which acts as a base for the vocabulary.
  *                                   In Json-LD is usually the "@vocab" predicate
  * @param discriminatorPredicate     the Uri predicate that acts as a discriminator.
  *                                   In Json-LD is usually the "@type" predicate
  * @param transformMemberNames       a map with field name transformations
  * @param idMemberName               the field in the ADT that is used as the root node.
  *                                   In Json-LD this is usually the "@id" predicate
  * @param includeRootConstructorName flag to decide whether or not the root name of the hierarchy should be added to the resulting graph.
  *                                   E.g.: Having an ADT like ''Animal -> Dog'' and includeRootConstructorName = true will
  *                                   include as a discriminatorPredicate "Dog" and "Animal". If includeRootConstructorName = false
  *                                   only the discriminatorPredicate "Dog" will be included
  */
final case class Configuration(
    base: Uri,
    discriminatorPredicate: Uri,
    transformMemberNames: String => String,
    idMemberName: String,
    includeRootConstructorName: Boolean
) {
  private[derivation] def predicate(label: String): Either[String, IriNode] =
    iriNodeFromBase(transformMemberNames(label))

  private[derivation] def iriNodeFromBase(suffix: String): Either[String, IriNode] =
    Iri.uri(base.iriString + suffix).map(IriNode.apply).leftMap(_ => base.iriString + suffix)

}

object Configuration {
  val default: Configuration =
    Configuration(nxv, rdf.tpe, identity, "id", includeRootConstructorName = true)
}

object defaults {
  implicit final val defaultConfiguration: Configuration = Configuration.default
}
