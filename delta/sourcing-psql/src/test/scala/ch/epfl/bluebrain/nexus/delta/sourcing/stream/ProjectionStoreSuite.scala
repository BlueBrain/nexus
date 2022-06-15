package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.{SourceId, SourceIdPipeChainId}
import ch.epfl.bluebrain.nexus.delta.sourcing.{DoobieAssertions, DoobieFixture, MonixBioSuite}

class ProjectionStoreSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ProjectionStore(xas)

  private val name   = "offset"
  private val sid    = iri"https://source"
  private val pid    = iri"https://pipe"
  private val offset = ProjectionOffset(
    Map(
      SourceId(sid)                 -> Offset.at(1L),
      SourceIdPipeChainId(sid, pid) -> Offset.at(2L)
    )
  )
  private val and    = ProjectionOffset(SourceId(iri"https://and"), Offset.at(3L))

  test("Return an empty offset when not found") {
    for {
      offset <- store.offset("not found")
      _       = assertEquals(offset, ProjectionOffset.empty)
    } yield ()
  }

  test("Create an offset") {
    for {
      _    <- store.save(name, offset)
      read <- store.offset(name)
      _     = assertEquals(read, offset)
    } yield ()
  }

  test("Update an offset") {
    for {
      read  <- store.offset(name)
      _      = assertEquals(read, offset)
      _     <- store.save(name, offset |+| and)
      again <- store.offset(name)
      _      = assertEquals(again, offset |+| and)
    } yield ()
  }

  test("Delete an offset") {
    for {
      read  <- store.offset(name)
      _      = assertEquals(read, offset |+| and)
      _     <- store.delete(name)
      again <- store.offset(name)
      _      = assertEquals(again, ProjectionOffset.empty)
    } yield ()
  }

}
