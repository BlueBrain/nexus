package ch.epfl.bluebrain.nexus.ship.views

import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.config.InputConfig.ProjectMapping
import io.circe.Json
import io.circe.optics.JsonPath.root

class ViewPatcher(mapping: ProjectMapping) {

  def patchAggregateViewSource(input: Json): Json =
    root.views.each.project.string.modify { p =>
      val projectRef = parseProjectRef(p)
      mapping.getOrElse(projectRef, projectRef).toString
    }(input)

  private def parseProjectRef(p: String) = ProjectRef
    .parse(p)
    .getOrElse(throw new IllegalArgumentException(s"Invalid project ref found in aggregate view source: $p"))
}
