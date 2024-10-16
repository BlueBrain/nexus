package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

import java.util.UUID

class S3LocationGeneratorSuite extends NexusSuite {

  test("Generate the expected uri") {
    val prefix   = Path("/prefix")
    val project  = ProjectRef.unsafe("org", "project")
    val uuid     = UUID.fromString("12345678-b2e3-40b9-93de-c809415d7640")
    val filename = "cat.gif"

    val generator = new S3LocationGenerator(prefix)

    val expected = Uri("/prefix/org/project/files/1/2/3/4/5/6/7/8/cat.gif")

    assertEquals(generator.file(project, uuid, filename), expected)
  }

}
