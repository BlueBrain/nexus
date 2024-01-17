package ch.epfl.bluebrain.nexus.delta.kernel.utils

import com.github.jknack.handlebars.{EscapingStrategy, Handlebars, Helper, Options}

import scala.jdk.CollectionConverters._

class HandlebarsExpander {

  private val handleBars = new Handlebars()
    .`with`(EscapingStrategy.NOOP)

  def expand(templateText: String, attributes: Map[String, Any]) = {
    if (attributes.isEmpty) {
      templateText
    } else {
      handleBars.compileInline(templateText).apply(attributes.asJava)
    }
  }
}
