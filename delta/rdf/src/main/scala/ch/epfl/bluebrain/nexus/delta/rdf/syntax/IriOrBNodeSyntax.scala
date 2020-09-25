package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}

trait IriSyntax {
  implicit final def iriStringContextSyntax(sc: StringContext): IriStringContextOps     = new IriStringContextOps(sc)
  implicit final def bnodeStringContextSyntax(sc: StringContext): BNodeStringContextOps = new BNodeStringContextOps(sc)
  implicit final def iriStringSyntax(string: String): IriStringOps                      = new IriStringOps(string)
}

final class IriStringContextOps(private val sc: StringContext) extends AnyVal {

  /**
    * Construct an Iri without checking the validity of the format.
    */
  def iri(args: Any*): Iri = Iri.unsafe(sc.s(args: _*))
}

final class IriStringOps(private val string: String) extends AnyVal {

  /**
    * Attempts to construct an absolute Iri, returning a Left when it does not have the correct Iri format.
    */
  def toIri: Either[String, Iri] = Iri.absolute(string)
}

final class BNodeStringContextOps(private val sc: StringContext) extends AnyVal {

  /**
    * Construct a [[BNode]].
    */
  def bnode(args: Any*): BNode = BNode.unsafe(sc.s(args: _*))
}
