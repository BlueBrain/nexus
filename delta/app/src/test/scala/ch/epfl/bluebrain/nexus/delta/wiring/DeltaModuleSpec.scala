package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOValues}
import com.typesafe.config.Config
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class DeltaModuleSpec extends AnyWordSpecLike with Matchers with IOValues {

  "DeltaModule" should {

    "load correctly" in {
      val ref: IORef[Option[Locator]] = IORef.unsafe(None)
      AppConfig
        .load()
        .flatMap { case (cfg: AppConfig, config: Config) =>
          Injector()
            .produceF[Task](DeltaModule(cfg, config), Roots.Everything)
            .use(locator => ref.set(Some(locator)))
        }
        .accepted

      ref.get.accepted shouldBe defined
    }
  }

}
