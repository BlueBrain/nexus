package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encode
import ch.epfl.bluebrain.nexus.delta.plugins.archive.FileSelf.ParsingError._
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import monix.bio.UIO

class FileSelfSuite extends BioSuite {

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
  private val fileSelf                 = FileSelf(FetchContextDummy(List(projectObj)).mapRejection(ProjectContextRejection))

  test("An expanded self should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/${encode(expandedResourceId)}"
    fileSelf.parse(input).tapError { p => UIO.delay(println(p)) }.assert((projectRef, latestRef))
  }

  test("An expanded self with a revision should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/${encode(expandedResourceId)}?rev=$rev"
    fileSelf.parse(input).tapError { p => UIO.delay(println(p)) }.assert((projectRef, revisionRef))
  }

  test("An expanded self with a tag should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/${encode(expandedResourceId)}?tag=${tag.value}"
    fileSelf.parse(input).tapError { p => UIO.delay(println(p)) }.assert((projectRef, tagRef))
  }

  test("A curie self should be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/nxv:$compactResourceId"
    fileSelf.parse(input).assert((projectRef, latestRef))
  }

  test("A relative self should not be parsed") {
    val input = iri"/$org/$project/$compactResourceId"
    fileSelf.parse(input).error(NonAbsoluteLink(input))
  }

  test("A self from an external website should not be parsed") {
    val input = iri"http://localhost/v1/files/$org/$project/$compactResourceId"
    fileSelf.parse(input).error(ExternalLink(input))
  }

  test("A self with an incorrect path should not be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/$org/$project/$compactResourceId/extra"
    fileSelf.parse(input).error(InvalidPath(input))
  }

  test("A self with an incorrect project label should not be parsed") {
    val input = iri"http://bbp.epfl.ch/v1/files/%illegal/$project/$compactResourceId"
    fileSelf.parse(input).error(InvalidProject(input))
  }

  test("A self with an incorrect id should not resolve") {
    val input = iri"""http://bbp.epfl.ch/v1/files/$org/$project/badcurie:$compactResourceId")}"""
    fileSelf.parse(input).error(InvalidFileId(input))
  }
}
