package ch.epfl.bluebrain.nexus.kg.archives

import java.time.{Clock, Instant, ZoneId}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Randomness}
import ch.epfl.bluebrain.nexus.commons.test.io.IOEitherValues
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.archives.Archive.{File, Resource}
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.{InvalidResourceFormat, ProjectRefNotFound}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectLabel
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, Inspectors}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class ArchiveSpec
    extends TestKit(ActorSystem("ArchiveSpec"))
    with AnyWordSpecLike
    with Matchers
    with TestHelper
    with Randomness
    with EitherValues
    with Inspectors
    with IOEitherValues
    with IdiomaticMockito
    with BeforeAndAfter {

  implicit private val cache            = mock[ProjectCache[IO]]
  private val appConfig                 = Settings(system).serviceConfig
  implicit private val config           =
    appConfig.copy(kg =
      appConfig.kg.copy(archives = appConfig.kg.archives.copy(cacheInvalidateAfter = 1.second, maxResources = 3))
    )
  implicit private val clock            = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())
  implicit private val subject: Subject = Anonymous
  implicit private val archivesCfg      = config.kg.archives

  private def addField[A: Encoder](tuple: (String, Option[A])): Json =
    tuple match {
      case (name, Some(v)) => Json.obj(name -> v.asJson)
      case _               => Json.obj()
    }

  before {
    Mockito.reset(cache)
  }

  abstract private class Ctx {
    def randomProject() = {
      val instant = Instant.EPOCH
      // format: off
      Project(genIri, genString(), genString(), None, genIri, genIri, Map.empty, genUUID, genUUID, 1L, false, instant, genIri, instant, genIri)
      // format: on
    }

    // format: off
    implicit val project = randomProject()
    // format: on

    val id  = Id(project.ref, genIri)
    def jsonResource(
        id: Option[AbsoluteIri] = None,
        rev: Option[Long] = None,
        tag: Option[String] = None,
        originalSource: Option[Boolean] = None,
        path: Option[String] = None,
        project: Option[String] = None
    ): Json =
      jsonFromInitial(
        Json.obj("@type" -> nxv.Resource.value.asString.asJson),
        id,
        rev,
        tag,
        originalSource,
        path,
        project
      )

    def jsonFile(
        id: Option[AbsoluteIri] = None,
        rev: Option[Long] = None,
        tag: Option[String] = None,
        path: Option[String] = None,
        project: Option[String] = None
    ): Json =
      jsonFromInitial(Json.obj("@type" -> nxv.File.value.asString.asJson), id, rev, tag, None, path, project)

    private def jsonFromInitial(
        json: Json,
        id: Option[AbsoluteIri],
        rev: Option[Long],
        tag: Option[String],
        originalSource: Option[Boolean],
        path: Option[String],
        project: Option[String]
    ): Json =
      json deepMerge
        addField("resourceId" -> id) deepMerge addField("rev" -> rev) deepMerge addField("tag" -> tag) deepMerge
        addField("originalSource" -> originalSource) deepMerge
        addField("path" -> path) deepMerge addField("project" -> project)

    def graph(jsons: Json*): Graph = {
      val json =
        Json
          .obj(
            "@id"       -> id.value.toString().asJson,
            "resources" -> Json.arr(jsons: _*),
            "@type"     -> "Archive".asJson
          )
          .appendContextOf(archiveCtx)
      json.toGraph(id.value).rightValue
    }

    def error(field: String) = s"'$field' field does not have the right format."
  }

  "An Archive" should {

    "converted to a bundle correctly" in new Ctx {
      val id1             = genIri
      val rev1            = genInt().toLong
      val originalSource1 = true
      val path1Str        = s"${genString()}/${genString()}/${genString()}.ext"
      val path1           = Path.rootless(path1Str).rightValue
      val resource1Json   = jsonResource(
        id = Some(id1),
        rev = Some(rev1),
        originalSource = Some(originalSource1),
        path = Some(path1Str)
      )
      val resource1       = Resource(id1, project, Some(rev1), None, originalSource1, Some(path1))

      val tag2      = genString()
      val id2       = genIri
      val file2Json = jsonFile(id = Some(id2), tag = Some(tag2))
      val file2     = File(id2, project, None, Some(tag2), None)
      Archive[IO](id.value, graph(resource1Json, file2Json)).value.accepted shouldEqual
        Archive(id, clock.instant, subject, Set(resource1, file2))
    }

    "converted to a bundle correctly using project cache" in new Ctx {
      val project1Data  = randomProject()
      val project2Data  = randomProject()
      val id1           = genIri
      val org1          = genString()
      val project1      = genString()
      cache.get(ProjectLabel(org1, project1)) shouldReturn IO(Some(project1Data))
      val resource1Json = jsonResource(id = Some(id1), project = Some(s"$org1/$project1"))
      val resource1     = Resource(id1, project1Data, None, None, true, None)

      val tag2      = genString()
      val id2       = genIri
      val org2      = genString()
      val project2  = genString()
      cache.get(ProjectLabel(org2, project2)) shouldReturn IO(Some(project2Data))
      val file2Json = jsonFile(id = Some(id2), tag = Some(tag2), project = Some(s"$org2/$project2"))
      val file2     = File(id2, project2Data, None, Some(tag2), None)
      Archive[IO](id.value, graph(resource1Json, file2Json)).value.accepted shouldEqual
        Archive(id, clock.instant, subject, Set(resource1, file2))
    }

    "reject when resource element contains wrong type" in new Ctx {
      val id1           = genIri
      val resource1Json = jsonResource(id = Some(id1)) deepMerge
        Json.obj("@type" -> Json.fromString("http://example.com/wrong"))
      Archive[IO](id.value, graph(resource1Json)).value.rejected[InvalidResourceFormat]
    }

    "reject when project is not found on the cache" in new Ctx {
      val id1           = genIri
      val org1          = genString()
      val project1      = genString()
      cache.get(ProjectLabel(org1, project1)) shouldReturn IO(None)
      val resource1Json = jsonResource(id = Some(id1), project = Some(s"$org1/$project1"))

      Archive[IO](id.value, graph(resource1Json)).value.rejected[ProjectRefNotFound] shouldEqual
        ProjectRefNotFound(ProjectLabel(org1, project1))
    }

    "reject when fields are not matching the target type" in new Ctx {
      val id1  = genIri
      val list = List[(String, Json)](
        "originalSource" -> (jsonResource(id = Some(id1)) deepMerge Json.obj("originalSource" -> genString().asJson)),
        "rev"            -> (jsonResource(id = Some(id1)) deepMerge Json.obj("rev" -> genString().asJson)),
        "tag"            -> (jsonResource(id = Some(id1)) deepMerge Json.obj("tag" -> genInt().asJson)),
        "path"           -> (jsonResource(id = Some(id1)) deepMerge Json.obj("path" -> s"/${genString()}".asJson))
      )
      forAll(list) {
        case (field, json) =>
          Archive[IO](id.value, graph(json)).value.rejected[InvalidResourceFormat] shouldEqual
            InvalidResourceFormat(id1.ref, error(field))
      }
    }

    "rejection when too many resources" in new Ctx {
      val resources = List.fill(4)(jsonResource(id = Some(genIri)))
      Archive[IO](id.value, graph(resources: _*)).value.rejected[InvalidResourceFormat] shouldEqual
        InvalidResourceFormat(
          id.ref,
          s"Too many resources. Maximum resources allowed: '${config.kg.archives.maxResources}'. Found: '4'"
        )
    }

    "reject when resourceId is missing" in new Ctx {
      Archive[IO](id.value, graph(Json.obj())).value.rejected[InvalidResourceFormat] shouldEqual
        InvalidResourceFormat(id.ref, error("resourceId"))
    }

    "reject duplicated paths" in new Ctx {
      val path          = s"${genString()}/${genString()}/${genString()}.ext"
      val resource1Json = jsonResource(id = Some(genIri), path = Some(path))
      val resource2Json = jsonResource(id = Some(genIri), path = Some(path))
      Archive[IO](id.value, graph(resource1Json, resource2Json)).value.rejected[InvalidResourceFormat] shouldEqual
        InvalidResourceFormat(id.ref, "Duplicated 'path' fields")
    }

    "reject duplicated parent paths" in new Ctx {
      val s1            = genString()
      val s2            = genString()
      val path          = s"$s1/$s2/${genString()}.ext"
      val path2         = s"$s1/$s2"
      val resource1Json = jsonResource(id = Some(genIri), path = Some(path))
      val resource2Json = jsonResource(id = Some(genIri), path = Some(path2))
      Archive[IO](id.value, graph(resource1Json, resource2Json)).value.rejected[InvalidResourceFormat] shouldEqual
        InvalidResourceFormat(id.ref, "Duplicated 'path' fields")
    }

    "reject wrong paths" in new Ctx {
      val path          = s"${genString()}/${genString()}/"
      val idElem        = genIri
      val resource1Json = jsonResource(id = Some(idElem), path = Some(path))
      Archive[IO](id.value, graph(resource1Json)).value.rejected[InvalidResourceFormat] shouldEqual
        InvalidResourceFormat(idElem.ref, "'path' field does not have the right format.")
    }
  }

}
