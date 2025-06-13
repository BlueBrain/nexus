package ai.senscience.nexus.delta.otel

import ai.senscience.nexus.delta.config.DescriptionConfig
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.typelevel.otel4s.oteljava.OtelJava

/**
  * Initialize OpenTelemetry with the description config but relying mostly on autoconfiguration
  *
  * @see
  *   https://typelevel.org/otel4s/sdk/configuration.html
  */
object OpenTelemetryInit {

  private val logger = Logger[OpenTelemetryInit.type]

  // Open telemetry is disabled by default
  private def disabled: Boolean =
    sys.props.getOrElse("otel.sdk.disabled", "true").toBooleanOption.getOrElse(true) &&
      sys.env.getOrElse("OTEL_SDK_DISABLED", "true").toBooleanOption.getOrElse(true)

  def apply(description: DescriptionConfig): Resource[IO, OtelJava[IO]] =
    if (disabled) {
      Resource.eval(OtelJava.noop[IO]).evalTap { _ =>
        logger.info("OpenTelemetry is disabled.")
      }
    } else {
      sys.props.getOrElseUpdate("otel.service.name", description.name.value)
      OtelJava.autoConfigured[IO]().evalTap { otel =>
        IO.delay {
          OpenTelemetryAppender.install(otel.underlying)
        } >> logger.info("OpenTelemetry is enabled.")
      }
    }
}
