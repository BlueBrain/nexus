package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Files, Path}

import cats.effect.Sync
import cats.implicits._
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, ConfigValue}

import scala.util.Try

/**
  * A config writer. The pipeline is as follows: A -> (typesafe)Config -> string -> file
  *
  * @tparam A the configuration type
  */
class ConfigWriter[A, F[_]](implicit F: Sync[F], writer: pureconfig.ConfigWriter[A]) {
  private val renderConfigOpts = ConfigRenderOptions.defaults().setOriginComments(false).setJson(false)

  private def appConfig(config: ConfigValue): ConfigValue =
    ConfigFactory.empty().withValue("app", config).root()

  /**
    * Attempts to convert the passed ''config'' to a (typesafe)Config and write the result to the passed ''path'' location.
    */
  def apply(config: A, path: Path): F[Either[String, Unit]] =
    for {
      configString <- F.delay(appConfig(writer.to(config)).render(renderConfigOpts))
      writeResult  <- F.delay(Try(Files.writeString(path, configString)).map(_ => ()).toEither.leftMap(_.getMessage))
    } yield writeResult
}

object ConfigWriter {
  implicit def configWriterInstance[A: pureconfig.ConfigWriter, F[_]: Sync]: ConfigWriter[A, F] = new ConfigWriter[A, F]
}
