package ch.epfl.bluebrain.nexus.testkit.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.utils.{CatsEffectsClasspathResourceUtils, FilesCache}
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext

trait FilesCacheFixture { self: CatsRunContext =>
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  protected val filesCache: FilesCache =
    FilesCache.mk(CatsEffectsClasspathResourceUtils.ioJsonObjectContentOf(_)).unsafeRunSync()
}
