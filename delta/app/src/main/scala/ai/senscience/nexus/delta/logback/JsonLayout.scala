package ai.senscience.nexus.delta.logback

import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
import ch.qos.logback.core.LayoutBase
import io.circe.Json
import io.circe.syntax.*

class JsonLayout extends LayoutBase[ILoggingEvent] {
  override def doLayout(event: ILoggingEvent): String = {
    val stackTraceFields = Option(event.getThrowableProxy)
      .map(e => List("error.type" := e.getClassName, "error.stack_trace" := ThrowableProxyUtil.asString(e)))
      .getOrElse(Nil)
    Json
      .fromFields(
        Map(
          "@timestamp" := event.getInstant,
          "message"    := event.getFormattedMessage,
          "log.level"  := event.getLevel.toString,
          "log.logger" := event.getLoggerName
        ) ++ stackTraceFields
      )
      .noSpaces + '\n'
  }
}
