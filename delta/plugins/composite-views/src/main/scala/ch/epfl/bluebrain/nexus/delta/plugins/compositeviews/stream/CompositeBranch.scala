package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import doobie.{Get, Put}

/**
  * Defines metadata for a sub projection in a composite view
  * @param source
  *   the source for the sub projection
  * @param target
  *   the target for the sub projection
  * @param run
  *   if the
  */
final case class CompositeBranch(source: Iri, target: Iri, run: Run)

object CompositeBranch {

  sealed trait Run extends Product with Serializable {
    def value: String
  }

  object Run {
    implicit val runGet: Get[Run] = Get[String].temap {
      case Main.value    => Right(Main)
      case Rebuild.value => Right(Rebuild)
      case value         => Left(s"'$value' is not value for `Run`")
    }
    implicit val runPut: Put[Run] = Put[String].contramap(_.value)

    case object Main extends Run {
      override val value = "main"
    }

    case object Rebuild extends Run {
      override val value = "rebuild"
    }
  }

}
