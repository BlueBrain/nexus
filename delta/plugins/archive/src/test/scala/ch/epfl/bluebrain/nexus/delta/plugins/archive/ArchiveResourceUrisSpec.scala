package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveResourceUris
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.RootResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class ArchiveResourceUrisSpec extends AnyWordSpecLike with Matchers with TestHelpers {

  private val uuid     = UUID.randomUUID()
  private val am       = ApiMappings("nxv" -> nxv.base, "Person" -> schema.Person)
  private val projBase = iri"http://localhost/base/"
  private val project  =
    ProjectGen.project("org", "project", uuid = uuid, orgUuid = uuid, base = projBase, mappings = am)

  "An ArchiveResourceUris" should {

    "yield a correct RootResourceUris" in {
      val suffix    = genString()
      val id        = iri"http://localhost/base/$suffix"
      val encodedId = URLEncoder.encode(id.toString, StandardCharsets.UTF_8)
      val expected  = RootResourceUris(
        s"archives/org/project/$encodedId",
        s"archives/org/project/$suffix"
      )
      ArchiveResourceUris(id, project.ref, project.apiMappings, project.base) shouldEqual expected
    }
  }

}
