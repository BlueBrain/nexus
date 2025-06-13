package ai.senscience.nexus.delta.logback

import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
import ch.qos.logback.core.encoder.EncoderBase
import io.circe.syntax.KeyOps
import io.circe.{Json, Printer}
import io.opentelemetry.semconv.ExceptionAttributes.*

import scala.jdk.CollectionConverters.*

class OtelJsonEncoder extends EncoderBase[ILoggingEvent] {

  private val printer: Printer = Printer(dropNullValues = true, indent = "")

  override def headerBytes(): Array[Byte] = Array.emptyByteArray

  private val exceptionType       = EXCEPTION_TYPE.getKey
  private val exceptionMessage    = EXCEPTION_MESSAGE.getKey
  private val exceptionStacktrace = EXCEPTION_STACKTRACE.getKey

  override def encode(event: ILoggingEvent): Array[Byte] = {
    val exceptionFields =
      Option(event.getThrowableProxy)
        .map { e =>
          Map(
            exceptionType       := e.getClassName,
            exceptionMessage    := e.getMessage,
            exceptionStacktrace := ThrowableProxyUtil.asString(e)
          )
        }
        .getOrElse(Map.empty)

    val mdcFields = event.getMDCPropertyMap.asScala.map { case (key, value) =>
      key := value
    }

    val attributes = Json.fromFields(
      Map("LoggerName" := event.getLoggerName) ++ exceptionFields ++ mdcFields
    )
    val json       = Json.fromFields(
      Map(
        "SeverityText"   := event.getLevel.toString,
        "SeverityNumber" := event.getLevel.levelInt,
        "Timestamp"      := event.getInstant,
        "Body"           := event.getFormattedMessage,
        "Attributes"     := attributes
      )
    )
    (printer.print(json) + '\n').getBytes
  }

  override def footerBytes(): Array[Byte] = Array.emptyByteArray
}
