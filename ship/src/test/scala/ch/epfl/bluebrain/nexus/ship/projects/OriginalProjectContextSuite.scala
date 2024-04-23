package ch.epfl.bluebrain.nexus.ship.projects

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture

class OriginalProjectContextSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val originalProjectContext = new OriginalProjectContext(xas)

  private val project = ProjectRef.unsafe("org", "proj")

  private val context = ProjectContext(
    ApiMappings("test" -> (nxv + "test")),
    ProjectBase.unsafe(nxv + "base"),
    nxv + "vocab",
    enforceSchema = true
  )

  test("Save and get back and overwrite") {
    for {
      _             <- originalProjectContext.save(project, context)
      _             <- originalProjectContext.onRead(project).assertEquals(context)
      updatedContext = context.copy(enforceSchema = false)
      _             <- originalProjectContext.save(project, updatedContext)
      _             <- originalProjectContext.onRead(project).assertEquals(updatedContext)
    } yield ()
  }

}
