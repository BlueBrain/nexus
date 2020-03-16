package ch.epfl.bluebrain.nexus.rdf

package object syntax {

  object all  extends IriSyntax with NodeSyntax
  object iri  extends IriSyntax
  object node extends NodeSyntax

}
