package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO

import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import cats.effect.Ref
import cats.implicits._

class MD5Suite extends CatsEffectSuite {

  test("a string should have the correct MD5 hash") {
    val input        = "bbp/atlas"
    val hash         = MD5.hash(input)
    val expectedHash = "5741353a5fa12bd21cc6c19ecc97b256"
    assertEquals(hash, expectedHash)
  }

  test("MD5 implementation should be thread safe") {
    val projectRef = "organization/project"
    val cache      = Ref.unsafe[IO, Set[String]](Set.empty)
    val xs         = List.fill(100)(projectRef)
    val task       = xs.parTraverse { x =>
      cache.get.flatMap { c =>
        val hash = MD5.hash(x)
        if (c.contains(hash)) IO.unit
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
