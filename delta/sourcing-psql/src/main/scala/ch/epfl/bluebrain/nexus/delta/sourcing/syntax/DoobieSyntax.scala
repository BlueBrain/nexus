package ch.epfl.bluebrain.nexus.delta.sourcing.syntax

import ch.epfl.bluebrain.nexus.delta.sourcing.FragmentEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax.DoobieSyntax.FragmentEncoderOps
import doobie.util.fragment.Fragment

/**
  * This package provides syntax via enrichment classes for Doobie
  */
trait DoobieSyntax {

  implicit final def fragmentEncoderOps[A](value: A): FragmentEncoderOps[A] = new FragmentEncoderOps[A](value)

}

object DoobieSyntax {
  implicit class FragmentEncoderOps[A](private val value: A) extends AnyVal {
    def asFragment(implicit encoder: FragmentEncoder[A]): Option[Fragment] = encoder(value)
  }
}
