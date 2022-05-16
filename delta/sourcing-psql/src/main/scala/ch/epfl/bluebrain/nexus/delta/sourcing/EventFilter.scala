package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import doobie._
import doobie.implicits._

final case class EventFilter(entityType: Option[EntityType], org: Option[Label], project: Option[ProjectRef]) {
  def toFragment: doobie.Fragment = Fragments.andOpt(
    entityType.map { t => fr"type = $t" },
    org.map { o => fr"org = $o" },
    project.map { p => fr"project = $p" }
  )
}
