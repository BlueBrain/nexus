package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import doobie.syntax.all._

/**
  * Allows to filter results when querying the database for scoped entities
  */
sealed trait Scope extends Product with Serializable

object Scope {

  val root: Scope = Root

  def apply(project: ProjectRef): Scope = Project(project)

  /**
    * Get all results for any org and any project
    */
  final case object Root extends Scope

  /**
    * Get all results within the given org
    */
  final case class Org(label: Label) extends Scope

  /**
    * Get all results within the given project
    */
  final case class Project(ref: ProjectRef) extends Scope

  implicit val scopeFragmentEncoder: FragmentEncoder[Scope] = FragmentEncoder.instance {
    case Root         => None
    case Org(label)   => Some(fr"org = $label")
    case Project(ref) => Some(fr"org = ${ref.organization} and project = ${ref.project}")
  }

}
