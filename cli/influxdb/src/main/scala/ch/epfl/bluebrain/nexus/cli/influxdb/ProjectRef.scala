package ch.epfl.bluebrain.nexus.cli.influxdb

import ch.epfl.bluebrain.nexus.cli.types.Label

case class ProjectRef(orgLabel: Label, projectLabel: Label) {
  def asString: String = s"${orgLabel.value}/${projectLabel.value}"
}

object ProjectRef {
  def fromString(string: String): Option[ProjectRef] =
    string.split('/') match {
      case Array(first, second) => Some(ProjectRef(Label(first), Label(second)))
      case _                    => None
    }
}
