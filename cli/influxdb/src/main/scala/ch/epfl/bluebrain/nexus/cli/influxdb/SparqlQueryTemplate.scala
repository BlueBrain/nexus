package ch.epfl.bluebrain.nexus.cli.influxdb

import java.util.regex.Pattern
import ch.epfl.bluebrain.nexus.cli.influxdb.SparqlQueryTemplate.pattern

import org.http4s.Uri

case class SparqlQueryTemplate(value: String) {
  def inject(resourceId: Uri): String =
    value.replaceAll(pattern, resourceId.renderString)
}

object SparqlQueryTemplate {
  val pattern: String = Pattern.quote("{resource_id}")
}
