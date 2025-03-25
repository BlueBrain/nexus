package ch.epfl.bluebrain.nexus.delta.kernel.utils

import com.github.jknack.handlebars.{EscapingStrategy, Handlebars => JHandlebars}
import scala.jdk.CollectionConverters._

object Handlebars {

  private val instance = new JHandlebars().`with`(EscapingStrategy.NOOP)

  def apply(templateText: String, attributes: (String, Any)*): String = apply(templateText, attributes.toMap)

  def apply(templateText: String, attributes: Map[String, Any]): String = {
    if (attributes.isEmpty) {
      templateText
    } else {
      instance.compileInline(templateText).apply(attributes.asJava)
    }
  }
}
