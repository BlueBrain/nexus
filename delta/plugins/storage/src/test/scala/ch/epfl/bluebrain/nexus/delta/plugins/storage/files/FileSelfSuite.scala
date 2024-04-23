package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encode
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.FileSelf.ParsingError._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite

class FileSelfSuite extends NexusSuite {

  implicit private val baseUri: BaseUri = BaseUri("http://bbp.epfl.ch", Label.unsafe("v1"))

  private val compactResourceId        = "test-resource"
  private val resourceIri: Iri         = nxv + compactResourceId
  private val latestRef: ResourceRef   = ResourceRef(resourceIri)
  private val rev                      = 42
  private val revisionRef: ResourceRef = ResourceRef.Revision(resourceIri, rev)
  private val tag                      = UserTag.unsafe("my-tag")
  private val tagRef: ResourceRef      = ResourceRef.Tag(resourceIri, tag)
  private val expandedResourceId       = resourceIri.toString
  private val project                  = "testing-project"
  private val org                      = "epfl"
  private val projectObj               = ProjectGen.project(org, project)
  private val projectRef               = projectObj.ref
  private val fileSelf                 = FileSelf(FetchContextDummy(List(projectObj)))

  test("An expanded self should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/${encode(expandedResourceId)}"
    fileSelf.parse(input).assertEquals((projectRef, latestRef))
  }

  test("An expanded self with a revision should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/${encode(expandedResourceId)}?rev=$rev"
    fileSelf.parse(input).assertEquals((projectRef, revisionRef))
  }

  test("An expanded self with a tag should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/${encode(expandedResourceId)}?tag=${tag.value}"
    fileSelf.parse(input).assertEquals((projectRef, tagRef))
  }

  test("A curie self should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/nxv:$compactResourceId"
    fileSelf.parse(input).assertEquals((projectRef, latestRef))
  }

  test("A relative self should not be parsed") {
    val input = iri"/$org/$project/$compactResourceId"
    fileSelf.parse(input).interceptEquals(NonAbsoluteLink(input))
  }

  test("A self from an external website should not be parsed") {
    val input = iri"http://localhost/v1/files/$org/$project/$compactResourceId"
    fileSelf.parse(input).interceptEquals(ExternalLink(input))
  }

  test("A self with an incorrect path should not be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/$compactResourceId/extra"
    fileSelf.parse(input).interceptEquals(InvalidPath(input))
  }

  test("A self with an incorrect project label should not be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/%illegal/$project/$compactResourceId"
    fileSelf.parse(input).interceptEquals(InvalidProject(input))
  }

  test("A self with an incorrect id should not resolve") {
    val input = iri"""http://bbp.epfl.ch/v1/files/$org/$project/badcurie:$compactResourceId")}"""
    fileSelf.parse(input).interceptEquals(InvalidFileId(input))
  }
}
