package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.{SourceId, SourceIdPipeChainId}
import ch.epfl.bluebrain.nexus.delta.sourcing.{CollectionAssertions, DoobieAssertions, DoobieFixture, MonixBioSuite}

class ProjectionStoreSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions with CollectionAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ProjectionStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val name     = "offset"
  private val project  = ProjectRef.unsafe("org", "proj")
  private val resource = iri"https://resource"
  private val sid      = iri"https://source"
  private val pid      = iri"https://pipe"
  private val offset   = ProjectionOffset(
    Map(
      SourceId(sid)                 -> Offset.at(1L),
      SourceIdPipeChainId(sid, pid) -> Offset.at(2L)
    )
  )
  private val and      = ProjectionOffset(SourceId(iri"https://and"), Offset.at(3L))

  test("Return an empty offset when not found") {
    for {
      offset <- store.offset("not found")
      _       = assertEquals(offset, ProjectionOffset.empty)
    } yield ()
  }

  test("Return no entries") {
    for {
      entries <- store.entries.compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Create an offset") {
    for {
      _       <- store.save(name, Some(project), Some(resource), offset)
      read    <- store.offset(name)
      _        = assertEquals(read, offset)
      entries <- store.entries.compile.toList
      r        = entries.assertOneElem
      _        = assertEquals((r.name, r.project, r.resourceId, r.offset), (name, Some(project), Some(resource), offset))
      _        = assert(r.createdAt == r.updatedAt, "Created and updated at values are not identical after creation")
    } yield ()
  }

  test("Update an offset") {
    for {
      read    <- store.offset(name)
      _        = assertEquals(read, offset)
      _       <- store.save(name, None, None, offset |+| and)
      again   <- store.offset(name)
      _        = assertEquals(again, offset |+| and)
      entries <- store.entries.compile.toList
      r        = entries.assertOneElem
      _        = assertEquals((r.name, r.project, r.resourceId, r.offset), (name, None, None, offset |+| and))
    } yield ()
  }

  test("Delete an offset") {
    for {
      read    <- store.offset(name)
      _        = assertEquals(read, offset |+| and)
      _       <- store.delete(name)
      entries <- store.entries.compile.toList
      _        = entries.assertEmpty()
      again   <- store.offset(name)
      _        = assertEquals(again, ProjectionOffset.empty)
    } yield ()
  }

}
