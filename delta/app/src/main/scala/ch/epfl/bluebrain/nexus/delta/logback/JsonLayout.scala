package ch.epfl.bluebrain.nexus.delta.logback

import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
import ch.qos.logback.core.LayoutBase
import io.circe.Json
import io.circe.syntax._

class JsonLayout extends LayoutBase[ILoggingEvent] {
  override def doLayout(event: ILoggingEvent): String = {
    val stackTraceField = Option(event.getThrowableProxy).map("stackTrace" := ThrowableProxyUtil.asString(_))
    Json
      .fromFields(
        Map(
          "message"    := event.getMessage,
          "log.level"  := event.getLevel.toString,
          "@timestamp" := event.getInstant,
          "log.logger" := event.getLoggerName
        ) ++ stackTraceField
      )
      .noSpaces + '\n'
  }
}
