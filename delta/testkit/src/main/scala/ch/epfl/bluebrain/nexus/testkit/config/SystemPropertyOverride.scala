package ch.epfl.bluebrain.nexus.testkit.config

import cats.effect.{IO, Resource}
import com.typesafe.config.impl.ConfigImpl

object SystemPropertyOverride {

  private def reload: IO[Unit]                                              = IO.delay(ConfigImpl.reloadSystemPropertiesConfig())
  def apply(io: IO[Map[String, String]]): Resource[IO, Map[String, String]] = {
    def acquire                                        = io.flatTap { values =>
      IO.delay(values.foreach { case (k, v) => System.setProperty(k, v) }) >> reload
    }
    def release(values: Map[String, String]): IO[Unit] = IO.delay(
      values.foreach { case (k, _) => System.clearProperty(k) }
    )
    Resource.make(acquire)(release)
  }

  def apply(values: Map[String, String]): Resource[IO, Map[String, String]] = apply(IO.pure(values))

}
