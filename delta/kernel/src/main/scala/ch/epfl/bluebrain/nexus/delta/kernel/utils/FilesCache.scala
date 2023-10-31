package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.data.OptionT
import cats.effect.{IO, Timer}
import cats.implicits.toFlatMapOps
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.ioSyntaxLogErrors
import io.chrisdavenport.mules.MemoryCache
import io.circe.JsonObject
import org.typelevel.log4cats

trait FilesCache {
  def lookupLogErrors(resourcePath: String, msg: String): IO[JsonObject]
}

object FilesCache {

  implicit val log: log4cats.Logger[IO] = Logger.cats[FilesCache]

  def mk(fetchFromResources: String => IO[JsonObject])(implicit t: Timer[IO]): IO[FilesCache] =
    MemoryCache.ofSingleImmutableMap[IO, String, JsonObject](None).map(mk(fetchFromResources, _))

  def mk(fetchFromResources: String => IO[JsonObject], cache: MemoryCache[IO, String, JsonObject]): FilesCache =
    (resourcePath: String, msg: String) =>
      OptionT(cache.lookup(resourcePath))
        .getOrElseF(fetchFromResources(resourcePath).flatTap(cache.insert(resourcePath, _)))
        .logErrors(msg)
}
