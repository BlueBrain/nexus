package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import doobie.implicits._
import doobie.util.fragment.Fragment

/**
  * Allows to filter results when querying the database for scoped entities
  */
sealed trait Predicate extends Product with Serializable {
  def asFragment: Option[Fragment]
}

object Predicate {

  /**
    * Get all results for any org and any project
    */
  final case object Root extends Predicate {
    override def asFragment: Option[Fragment] = None
  }

  /**
    * Get all results within the given org
    */
  final case class Org(label: Label) extends Predicate {
    override def asFragment: Option[Fragment] = Some(fr"org = $label")
  }

  /**
    * Get all results within the given project
    */
  final case class Project(ref: ProjectRef) extends Predicate {
    override def asFragment: Option[Fragment] = Some(fr"org = ${ref.organization} and project = ${ref.project}")
  }

}


