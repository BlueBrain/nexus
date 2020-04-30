package ch.epfl.bluebrain.nexus.cli

import cats.effect.ConcurrentEffect
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.Console.{LiveConsole, TestConsole}
import distage.{ModuleDef, TagK}
import fs2.concurrent.Queue
import izumi.distage.model.definition.StandardAxis.Repo

class SharedModule[F[_]: ConcurrentEffect: TagK] extends ModuleDef {
  make[TestConsole[F]].tagged(Repo.Dummy).fromEffect {
    for {
      std <- Queue.circularBuffer[F, String](1000)
      err <- Queue.circularBuffer[F, String](1000)
    } yield new TestConsole[F](std, err)
  }
  make[Console[F]].tagged(Repo.Dummy).from { tc: TestConsole[F] => tc }
  make[Console[F]].tagged(Repo.Prod).from[LiveConsole[F]]
}

object SharedModule {
  def apply[F[_]: ConcurrentEffect: TagK]: SharedModule[F] = new SharedModule[F]
}
