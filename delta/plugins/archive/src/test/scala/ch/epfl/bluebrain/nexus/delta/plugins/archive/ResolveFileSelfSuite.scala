package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encode
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.IO
import munit.Location

class ResolveFileSelfSuite extends BioSuite {

  implicit private val baseUri: BaseUri = BaseUri(Uri("http://bbp.epfl.ch")).rightValue

  private val compactResourceId = "test-resource"
  private val resourceIri: Iri = nxv + compactResourceId
  private val resourceRef: ResourceRef = ResourceRef(resourceIri)
  private val expandedResourceId = resourceIri.toString
  private val project = "testing-project"
  private val org = "epfl"
  private val projectObj = ProjectGen.project(org, project)
  private val projectRef = projectObj.ref
  private val resolveSelf = ResolveFileSelf(FetchContextDummy(List(projectObj)).mapRejection(ProjectContextRejection))

  test("an absolute self should resolve") {
    val self = s"http://bbp.epfl.ch/files/$org/$project/${encode(expandedResourceId)}"
    assertEqualsIO(resolveSelf(self), (projectRef, resourceRef))
  }

  test("a curie self should resolve") {
    val self = s"http://bbp.epfl.ch/files/$org/$project/nxv:$compactResourceId"
    assertEqualsIO(resolveSelf(self), (projectRef, resourceRef))
  }

  test("a relative self should resolve") {
    val self = s"http://bbp.epfl.ch/files/$org/$project/$compactResourceId"
    assertEqualsIO(resolveSelf(self), (projectRef, resourceRef))
  }

  test("a self with an incorrect base uri should not resolve") {
    val self = s"http://different.base/files/$org/$project/$compactResourceId"
    assertErrorWithReason(resolveSelf(self), "did not start with base")
  }

  test("a self with an incorrect path should not resolve") {
    val self = s"http://bbp.epfl.ch/files/$org/$project/$compactResourceId/extra"
    assertErrorWithReason(resolveSelf(self), "parsing of path failed")
  }

  test("a self with an incorrect project label should not resolve") {
    val self = s"http://bbp.epfl.ch/files/%illegal/$project/$compactResourceId"
    assertErrorWithReason(resolveSelf(self), "project parsing failed")
  }

  test("a self with an incorrect id should not resolve") {
    val self = s"""http://bbp.epfl.ch/files/$org/$project/badcurie:$compactResourceId")}"""
    assertErrorWithReason(resolveSelf(self), "iri parsing failed")
  }

  private def assertErrorWithReason[A](obtained: IO[ArchiveRejection, A], expected: String)(implicit loc: Location) = {
    assertError[ArchiveRejection, A](obtained, _.reason.contains(expected), e => s"expected error reason to include '$expected', recieved '${e.reason}'")
  }
}
