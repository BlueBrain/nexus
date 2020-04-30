package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.Paths

import cats.effect.IO
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.cli.config.OffsetConfig._
import ch.epfl.bluebrain.nexus.cli.error.ConfigError.ReadConvertError
import ch.epfl.bluebrain.nexus.cli.types.Offset
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors}

class OffsetConfigSpec extends AnyWordSpecLike with Matchers with Fixtures with EitherValues with Inspectors {

  "An Offset Config" should {

    val offsetConfig = OffsetConfig(Some(Offset("b8a93f50-5c75-11ea-beb1-a5eb66b44d1c").value))

    "be created from a passed config file" in {
      val path = Paths.get(getClass.getResource("/offset-test.conf").toURI)
      OffsetConfig(path) shouldEqual Right(offsetConfig)
    }

    "be saved to a file" in {
      val paths = List(Paths.get(s"${genString()}.conf") -> false, defaultPath.toOption.value -> true)
      forAll(paths) {
        case (path, default) =>
          val write = if (default) offsetConfig.write[IO]() else offsetConfig.write[IO](path)
          write.unsafeRunSync() shouldEqual Right(())
          path.toFile.exists() shouldEqual true
          OffsetConfig(path) shouldEqual Right(offsetConfig)
          path.toFile.delete()
      }
    }

    "fail" in {
      val path = Paths.get(getClass.getResource("/offset-fail-test.conf").toURI)
      val err  = OffsetConfig(path).left.value
      err shouldBe a[ReadConvertError]
      err.show.contains("the application configuration failed to be loaded into a configuration object") shouldEqual true
    }

  }

}
