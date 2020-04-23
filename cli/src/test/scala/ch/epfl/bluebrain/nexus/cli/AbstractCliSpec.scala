package ch.epfl.bluebrain.nexus.cli

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.cli.dummies.TestCliModule
import ch.epfl.bluebrain.nexus.cli.utils.{Resources, ShouldMatchers}
import com.github.ghik.silencer.silent
import izumi.distage.model.definition.{ModuleDef, StandardAxis}
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.scalatest.DistageSpecScalatest

import scala.concurrent.ExecutionContext

abstract class AbstractCliSpec extends DistageSpecScalatest[IO] with Resources with ShouldMatchers {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  @silent
  override def config: TestConfig = TestConfig(
    pluginConfig = PluginConfig.empty,
    activation = StandardAxis.testDummyActivation,
    moduleOverrides = new ModuleDef {
      include(CliModule[IO])
      include(TestCliModule[IO])
      include(EffectModule[IO])
    },
    configBaseName = "cli-test"
  )
}
