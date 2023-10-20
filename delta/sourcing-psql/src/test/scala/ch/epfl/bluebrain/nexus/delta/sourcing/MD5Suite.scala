package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import monix.bio.Task

class MD5Suite extends BioSuite {

  test("a string should have the correct MD5 hash") {
    val input        = "bbp/atlas"
    val hash         = MD5.hash(input)
    val expectedHash = "5741353a5fa12bd21cc6c19ecc97b256"
    assertEquals(hash, expectedHash)
  }

  test("MD5 implementation should be thread safe") {
    val projectRef = "organization/project"
    val cache      = Ref.unsafe[Task, Set[String]](Set.empty)
    val xs         = List.fill(100)(projectRef)
    val task       = xs.parTraverse { x =>
      cache.get.flatMap { c =>
        val hash = MD5.hash(x)
        if (c.contains(hash)) Task.unit
        else cache.update(_ + hash)
      }
    }

    for {
      _ <- task
      c <- cache.get
      _  = c.assertOneElem
      _  = c.assertContains("3f33bc38009c0cfcda4fa7737f5fac85")
    } yield ()
  }

}
