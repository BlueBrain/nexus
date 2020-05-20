package ch.epfl.bluebrain.nexus

package object syntax {
  object all  extends JsonSyntax with PathSyntax
  object json extends JsonSyntax
  object path extends PathSyntax

}
